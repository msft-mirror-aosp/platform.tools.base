package com.example.bytecode.plugins;

import com.google.common.io.ByteStreams;
import java.io.*;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/** A task that generates bytecode simply by extracting it from a jar. */
public abstract class BytecodeGeneratingTask extends DefaultTask {

    private List<ConfigurableFileTree> sourceFolders;
    private File sourceJar;
    private FileCollection classpath;
    private String projectPath = getProject().getPath().toString();

    @InputFile
    public File getSourceJar() {
        return sourceJar;
    }

    @InputFiles
    @Optional
    public FileCollection getClasspath() {
        return classpath;
    }

    @InputFiles
    @Optional
    public List<ConfigurableFileTree> getSourceFolders() {
        return sourceFolders;
    }

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirProperty();

    public void setSourceFolders(List<ConfigurableFileTree> sourceFolders) {
        this.sourceFolders = sourceFolders;
    }
    public void setSourceJar(File sourceJar) {
        this.sourceJar = sourceJar;
    }

    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath;
    }

    @TaskAction
    void generate() throws IOException {
        File outputDir = getOutputDirProperty().get().getAsFile();
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new RuntimeException("Failed to mkdirs: " + outputDir);
        }

        try (InputStream fis = new BufferedInputStream(new FileInputStream(sourceJar));
                ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                try {
                    String name = entry.getName();

                    // do not take directories or manifest file
                    if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF")) {
                        continue;
                    }

                    File outputFile = new File(outputDir, name.replace('/', File.separatorChar));

                    final File parentFile = outputFile.getParentFile();
                    if (!parentFile.exists() && !parentFile.mkdirs()) {
                        throw new RuntimeException("Failed to mkdirs: " + parentFile);
                    }

                    try (OutputStream outputStream =
                            new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        ByteStreams.copy(zis, outputStream);
                        outputStream.flush();
                    }
                } finally {
                    zis.closeEntry();
                }
            }
        }

        // check the compile classpath
        if (classpath != null) {
            Set<File> files = classpath.getFiles();
            for (File file : files) {
                // Files that are task outputs may not exist yet.
                if (!file.exists()) continue;
                // Prints existing files for test validation.
                System.out.println(
                        "BytecodeGeneratingTask(" + projectPath + ":" + getName() + "): " + file);
            }
        }
    }
}
