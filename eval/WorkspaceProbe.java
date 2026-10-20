import com.demo.tools.fs.AtomicFileWriter;
import com.demo.tools.fs.WorkspaceFileAccess;
import java.nio.file.*;

/** Offline container smoke check for lower-layer directories and workspace identity checks. */
class WorkspaceProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("/workspace");
        var access = new WorkspaceFileAccess(root);
        var snapshot = access.read("src/CsvParser.java");
        new AtomicFileWriter(access).replace("src/CsvParser.java", "public class CsvParser",
                "/* offline write probe */ public class CsvParser", snapshot.sha256());
        access.verifyBinding();
        Files.createDirectory(root.resolve(".probe-classes"));
        access.verifyBinding();
        System.out.println("WRITE_AND_BINDING_OK");
    }
}
