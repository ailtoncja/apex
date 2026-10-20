import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
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
 * Gera o par de chaves Ed25519 que assina as atualizações do Apex, com a chave privada JÁ protegida por senha.
 * Uso:  java tools/GenKey.java <arquivo-da-chave-privada.key.enc>
 * A senha é pedida duas vezes no console (sem console, não gera nada). A chave privada fica SÓ no seu computador (nunca no GitHub);
 * a chave pública vai no app (UpdateKeys.kt).
 */
public class GenKey {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Uso: java tools/GenKey.java <arquivo-da-chave-privada.key.enc>");
            System.exit(2);
        }
        String name = args[0].endsWith(".enc") ? args[0] : args[0] + ".enc";
        Path out = Path.of(name);
        Path plain = Path.of(name.substring(0, name.length() - ".enc".length()));
        if (Files.exists(out) || Files.exists(plain)) {
            System.err.println("Já existe " + (Files.exists(out) ? out : plain) + ". Não vou sobrescrever uma chave que pode estar em uso.");
            System.exit(1);
        }
        Console console = System.console();
        if (console == null) {
            System.err.println("Rode num console de verdade (cmd ou PowerShell): a senha da chave só é pedida no console.");
            System.exit(1);
        }
        char[] pass = askNewPassphrase(console);
        if (pass == null) System.exit(1);

        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        try {
            String text = encrypt(pkcs8, pass);
            // Confere antes de gravar: o arquivo abre com a mesma senha e devolve a mesma chave.
            byte[] back = decrypt(text, pass);
            boolean same = Arrays.equals(back, pkcs8);
            Arrays.fill(back, (byte) 0);
            if (!same) throw new IllegalStateException("A chave cifrada não voltou igual. Nada foi gravado.");
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, text, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
            Arrays.fill(pass, '\0');
        }
        System.out.println("Chave privada (protegida por senha) gravada em: " + out);
        System.out.println("Chave pública (cole em UpdateKeys.kt): " + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        System.out.println("Guarde uma cópia do arquivo .enc e, SEPARADA dela, a senha (por exemplo: o .enc num pen drive guardado, a senha num gerenciador de senhas).");
    }

    /** Pede a senha duas vezes; null se não baterem ou se for curta demais. */
    static char[] askNewPassphrase(Console console) {
        char[] a = console.readPassword("Senha da chave (mínimo %d caracteres; uma frase longa é melhor): ", MIN_PASSPHRASE);
        char[] b = console.readPassword("Repita a senha: ");
        if (a == null || b == null) return null;
        boolean same = Arrays.equals(a, b);
        Arrays.fill(b, '\0');
        if (!same) {
            Arrays.fill(a, '\0');
            System.err.println("As senhas não são iguais. Nada foi gravado.");
            return null;
        }
        if (a.length < MIN_PASSPHRASE) {
            Arrays.fill(a, '\0');
            System.err.println("Senha curta demais (mínimo " + MIN_PASSPHRASE + " caracteres). Nada foi gravado.");
            return null;
        }
        return a;
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
