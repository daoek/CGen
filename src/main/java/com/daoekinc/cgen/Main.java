package com.daoekinc.cgen;

import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        FileHelper filehelper = new FileHelper();
        File file = filehelper.OpenFile("src/main/resources/Test.txt");

        Debug.print(file.getAbsolutePath());
    }
}
