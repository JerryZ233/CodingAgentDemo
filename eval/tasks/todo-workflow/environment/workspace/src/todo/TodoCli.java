package todo;

public final class TodoCli {
    public static void main(String[] args) {
        try {
            new TodoService().execute(args);
        } catch (Exception failure) {
            System.err.println(failure.getMessage());
            System.exit(2);
        }
    }
}
