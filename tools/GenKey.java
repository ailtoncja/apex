import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Gera o par de chaves Ed25519 que assina as atualizações do Apex.
 * Uso:  java tools/GenKey.java <arquivo-da-chave-privada>
 * A chave privada fica SÓ no seu computador (nunca no GitHub); a chave pública vai no app (UpdateKeys.kt).
 */
public class GenKey {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Uso: java tools/GenKey.java <arquivo-da-chave-privada>");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        if (Files.exists(out)) {
            System.err.println("Já existe " + out + ". Não vou sobrescrever uma chave que pode estar em uso.");
            System.exit(1);
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + System.lineSeparator());
        System.out.println("Chave privada gravada em: " + out);
        System.out.println("Chave pública (cole em UpdateKeys.kt): " + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }
}
