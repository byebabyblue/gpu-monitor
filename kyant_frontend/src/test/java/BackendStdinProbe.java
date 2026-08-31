/** Child process used to verify the real stdin/combined-output contract. */
public final class BackendStdinProbe {
    public static void main(String[] args) throws Exception {
        if (System.in.read() != -1) {
            throw new IllegalStateException("Expected EOF on backend stdin");
        }
        System.out.println("backend-stdin-closed");
        System.err.println("backend-stderr-captured");
    }
}
