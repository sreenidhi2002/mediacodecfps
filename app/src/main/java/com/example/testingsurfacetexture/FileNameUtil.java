package com.example.testingsurfacetexture;

import java.text.SimpleDateFormat;
import java.util.Date;

public class FileNameUtil {
    public static String getCurrentDateTimeFileName() {
        // Get the current date and time
        Date now = new Date();

        // Define the format for the date and time
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");

        // Format the current date and time
        String formattedDate = sdf.format(now);

        // Create the filename using the formatted date and time
        String fileName = "video_" + formattedDate + ".mp4";

        return fileName;
    }

    public static void main(String[] args) {
        // Example usage
        String fileName = getCurrentDateTimeFileName();
        System.out.println("Generated file name: " + fileName);
    }
}
