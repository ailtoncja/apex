import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Assina um arquivo com a chave privada Ed25519 e grava a assinatura (Base64) em <arquivo>.sig.
 * Uso:  java tools/Sign.java <arquivo-da-chave> <arquivo-a-assinar>
 * A chave pode ser a protegida por senha (<...>.key.enc, de GenKey.java ou ProtegerChave.java) ou a antiga sem senha (<...>.key).
 * Se existir <arquivo-da-chave>.enc, ele é sempre o preferido. A senha é pedida no console; para rodar sem ninguém digitando,
 * dá para passar na variável APEX_SIGN_PASSPHRASE (MENOS seguro: variáveis de ambiente ficam visíveis para outros programas do usuário).
 */
public class Sign {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Uso: java tools/Sign.java <arquivo-da-chave (.key.enc ou .key)> <arquivo-a-assinar>");
            System.exit(2);
        }
        Path given = Path.of(args[0]);
        Path enc = args[0].endsWith(".enc") ? given : Path.of(args[0] + ".enc");
        Path plain = args[0].endsWith(".enc") ? Path.of(args[0].substring(0, args[0].length() - ".enc".length())) : given;

        byte[] keyBytes;
        if (Files.isRegularFile(enc)) {
            char[] pass = passphrase(enc);
            if (pass == null) System.exit(1);
            try {
                keyBytes = decrypt(Files.readString(enc, StandardCharsets.US_ASCII), pass);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(1);
                return;
            } finally {
                Arrays.fill(pass, '\0');
            }
            if (Files.exists(plain)) {
                System.err.println("Aviso: ainda existe a chave SEM senha em " + plain + ". Apague-a (a protegida .enc já está em uso).");
            }
        } else if (Files.isRegularFile(plain)) {
            System.err.println("**************************************************************************************");
            System.err.println("ATENÇÃO: assinando com a chave SEM senha (" + plain + ").");
            System.err.println("Qualquer programa que rode com o seu usuário consegue copiá-la e assinar atualizações falsas.");
            System.err.println("Proteja-a uma vez com:  java tools\\ProtegerChave.java \"" + plain + "\"");
            System.err.println("**************************************************************************************");
            keyBytes = Base64.getDecoder().decode(Files.readString(plain, StandardCharsets.US_ASCII).trim());
        } else {
            System.err.println("Não encontrei a chave: nem " + enc + " nem " + plain + ".");
            System.exit(1);
            return;
        }

        byte[] signed;
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(Files.readAllBytes(Path.of(args[1])));
            signed = signature.sign();
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
        Path out = Path.of(args[1] + ".sig");
        Files.writeString(out, Base64.getEncoder().encodeToString(signed) + System.lineSeparator());
        System.out.println("Assinatura gravada em: " + out);
    }

    /** A senha da chave: da variável APEX_SIGN_PASSPHRASE (se existir) ou digitada no console. */
    static char[] passphrase(Path enc) {
        String env = System.getenv("APEX_SIGN_PASSPHRASE");
        if (env != null && !env.isEmpty()) {
            System.err.println("Usando a senha da variável APEX_SIGN_PASSPHRASE (menos seguro que digitar).");
            return env.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            System.err.println("A chave " + enc + " tem senha, mas não há console para digitá-la. Rode num cmd/PowerShell (ou defina APEX_SIGN_PASSPHRASE).");
            return null;
        }
        return console.readPassword("Senha da chave que assina as atualizações (%s): ", enc.getFileName());
    }

    // ---- Arquivo .key.enc: PBKDF2-HMAC-SHA256 (600 mil iterações, sal aleatório de 16 bytes) -> AES-256-GCM (nonce aleatório de 12 bytes)
    // ---- sobre os bytes PKCS#8 da chave. O cabeçalho (texto) entra como dado autenticado: mexer em qualquer campo faz a abertura falhar.
    // ---- Este bloco é IGUAL em GenKey.java, ProtegerChave.java e Sign.java (o java roda cada arquivo .java sozinho, sem os outros).

    static final String MAGIC = "APEX-UPDATE-KEY-ENC v1";
    static final int ITERATIONS = 600_000;
    static final int MIN_PASSPHRASE = 12;

    static String encrypt(byte[] pkcs8, char[] pass) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        String header = MAGIC + "\n"
            + "kdf=PBKDF2-HMAC-SHA256\n"
            + "iter=" + ITERATIONS + "\n"
            + "salt=" + Base64.getEncoder().encodeToString(salt) + "\n"
            + "cipher=AES-256-GCM\n"
            + "nonce=" + Base64.getEncoder().encodeToString(nonce) + "\n";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, derive(pass, salt, ITERATIONS), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header.getBytes(StandardCharsets.US_ASCII));
        return header + "data=" + Base64.getEncoder().encodeToString(cipher.doFinal(pkcs8)) + "\n";
    }

    static byte[] decrypt(String fileText, char[] pass) throws Exception {
        String text = fileText.replace("\r\n", "\n");
        int at = text.indexOf("\ndata=");
        if (!text.startsWith(MAGIC + "\n") || at < 0) throw new IllegalArgumentException("Não é um arquivo de chave protegida do Apex (" + MAGIC + ").");
        String header = text.substring(0, at + 1);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : header.substring(MAGIC.length() + 1).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) fields.put(line.substring(0, eq), line.substring(eq + 1));
        }
        if (!"PBKDF2-HMAC-SHA256".equals(fields.get("kdf")) || !"AES-256-GCM".equals(fields.get("cipher"))) {
            throw new IllegalArgumentException("Algoritmo desconhecido no arquivo da chave.");
        }
        int iterations = Integer.parseInt(fields.getOrDefault("iter", "0"));
        if (iterations < ITERATIONS || iterations > 50_000_000) throw new IllegalArgumentException("Número de iterações fora do esperado no arquivo da chave.");
        byte[] salt = Base64.getDecoder().decode(fields.getOrDefault("salt", ""));
        byte[] nonce = Base64.getDecoder().decode(fields.getOrDefault("nonce", ""));
        if (salt.length != 16 || nonce.length != 12) throw new IllegalArgumentException("Sal ou nonce com tamanho errado no arquivo da chave.");
        byte[] data = Base64.getDecoder().decode(text.substring(at + "\ndata=".length()).trim());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, derive(pass, salt, iterations), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header.getBytes(StandardCharsets.US_ASCII));
        try {
            return cipher.doFinal(data);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("Senha errada (ou o arquivo da chave foi alterado).");
        }
    }

    static SecretKeySpec derive(char[] pass, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, iterations, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(key, "AES");
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }
}
