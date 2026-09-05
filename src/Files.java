import java.io.File;

public class Files {

    public File OpenFile(String path)
    {
        File file = new File(path);

        if (!file.exists()) {
            return null;
        }

        if (file.canRead() && file.canWrite()) {
            return file;
        }

        return null;
    }



}
