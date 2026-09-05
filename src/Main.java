import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        Files files = new Files();
        File file = files.OpenFile("/storage/data/projects/CGen/src/Test.txt");
        
        Debug.print(file.getAbsolutePath());
    }
}
 