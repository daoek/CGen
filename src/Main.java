import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        FileHelper filehelper = new FileHelper();
        File file = filehelper.OpenFile("/storage/data/projects/CGen/src/Test.txt");
        
        if (file != null) {
            List<String> result = filehelper.ReadFile(file);
            for (String string : result) {
                Debug.print(string + "\n");
            }
        }

        Debug.print(file.getAbsolutePath());
    }
}
 