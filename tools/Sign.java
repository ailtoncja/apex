import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Assina um arquivo com a chave privada Ed25519 e grava a assinatura (Base64) em <arquivo>.sig.
 * Uso:  java tools/Sign.java <arquivo-da-chave-privada> <arquivo-a-assinar>
 */
public class Sign {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Uso: java tools/Sign.java <arquivo-da-chave-privada> <arquivo-a-assinar>");
            System.exit(2);
        }
        byte[] keyBytes = Base64.getDecoder().decode(Files.readString(Path.of(args[0])).trim());
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(Files.readAllBytes(Path.of(args[1])));
        Path out = Path.of(args[1] + ".sig");
        Files.writeString(out, Base64.getEncoder().encodeToString(signature.sign()) + System.lineSeparator());
        System.out.println("Assinatura gravada em: " + out);
    }
}
