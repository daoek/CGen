package com.daoekinc.cgen;

import java.io.*;
import java.util.List;

public class FileHelper {

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

    public List<String> ReadFile(File file)
    {
        List<String> data;

        try {
            
            BufferedReader buffer = new BufferedReader(new FileReader(file));
            data = buffer.lines().toList();
            buffer.close();

        } catch (Exception e) {
            return null;
        }

        return data;
    } 

    public boolean WriteFile(File file, List<String> data)
    {
        try {
            
            BufferedWriter buffer = new BufferedWriter(new FileWriter(file));

            for (String string : data) {
                buffer.append(string);
            }

            buffer.close();

        } catch (Exception e) {
            return false;
        }
        
        return true;

    }

}
