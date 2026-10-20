import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.SecureRandom;
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
 * Migração (uma vez só): protege com senha a chave que assina as atualizações, que até agora ficava em texto puro.
 * Uso:  java tools/ProtegerChave.java [arquivo-da-chave-privada]
 *       (sem argumento: %USERPROFILE%\.apex\signing\apex-update.key)
 * Lê a chave sem senha, pede a senha duas vezes no console, grava <arquivo>.enc, confere que ele abre e devolve a MESMA chave
 * e então oferece apagar o arquivo sem senha. Nunca mostra a chave.
 */
public class ProtegerChave {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            System.err.println("Uso: java tools/ProtegerChave.java [arquivo-da-chave-privada]");
            System.exit(2);
        }
        Path plain = args.length == 1 ? Path.of(args[0]) : Path.of(System.getProperty("user.home"), ".apex", "signing", "apex-update.key");
        Path enc = Path.of(plain + ".enc");
        if (!Files.isRegularFile(plain)) {
            System.err.println("Não encontrei a chave sem senha em " + plain + ".");
            System.exit(1);
        }
        if (Files.exists(enc)) {
            System.err.println("Já existe " + enc + ". Não vou sobrescrever (se ele já abre com a sua senha, só falta apagar " + plain + ").");
            System.exit(1);
        }
        Console console = System.console();
        if (console == null) {
            System.err.println("Rode num console de verdade (cmd ou PowerShell): a senha só é pedida no console.");
            System.exit(1);
        }

        byte[] pkcs8;
        try {
            pkcs8 = Base64.getDecoder().decode(Files.readString(plain, StandardCharsets.US_ASCII).trim());
            KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            System.err.println("O arquivo " + plain + " não tem uma chave Ed25519 válida. Nada foi feito.");
            System.exit(1);
            return;
        }

        char[] pass = askNewPassphrase(console);
        if (pass == null) {
            Arrays.fill(pkcs8, (byte) 0);
            System.exit(1);
        }
        try {
            Files.writeString(enc, encrypt(pkcs8, pass), StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            // Confere lendo do disco: o .enc abre com a senha e devolve exatamente a mesma chave.
            byte[] back = decrypt(Files.readString(enc, StandardCharsets.US_ASCII), pass);
            boolean same = Arrays.equals(back, pkcs8);
            Arrays.fill(back, (byte) 0);
            if (!same) {
                Files.deleteIfExists(enc);
                System.err.println("A chave cifrada não voltou igual. Apaguei o .enc; a chave sem senha continua onde estava.");
                System.exit(1);
            }
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
            Arrays.fill(pass, '\0');
        }
        System.out.println("Pronto: " + enc + " abre com a sua senha e tem a mesma chave.");
        System.out.println("Agora faça uma cópia do .enc fora do PC (pen drive guardado) e guarde a senha SEPARADA (gerenciador de senhas).");
        System.out.println("Cópias antigas da chave SEM senha (backups, pen drive, nuvem) também precisam ser apagadas.");

        String answer = console.readLine("Para apagar agora o arquivo SEM senha (%s), digite APAGAR; para manter, só Enter: ", plain);
        if (answer != null && answer.trim().equals("APAGAR")) {
            // Sobrescreve antes de apagar (ajuda em disco comum; em SSD não há garantia de que o conteúdo antigo some do disco).
            long size = Files.size(plain);
            byte[] junk = new byte[(int) Math.max(size, 1)];
            new SecureRandom().nextBytes(junk);
            Files.write(plain, junk, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.delete(plain);
            System.out.println("Apagado: " + plain);
        } else {
            System.out.println("Mantive " + plain + ". APAGUE-O assim que tiver testado o Empacotar.cmd com a senha:  del \"" + plain + "\"");
            System.out.println("(Enquanto ele existir, o Sign.java usa o .enc, mas quem copiar a pasta leva a chave sem senha.)");
        }
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
