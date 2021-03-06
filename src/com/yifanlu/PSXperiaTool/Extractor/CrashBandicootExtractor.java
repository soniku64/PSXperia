/*
 * PSXperia Converter Tool - Extractor
 * Copyright (C) 2011 Yifan Lu (http://yifan.lu/)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.yifanlu.PSXperiaTool.Extractor;

import brut.androlib.AndrolibException;
import brut.androlib.res.AndrolibResources;
import brut.androlib.res.data.ResTable;
import brut.androlib.res.util.ExtFile;
import com.yifanlu.PSXperiaTool.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import ie.wombat.jbdiff.JBPatch;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CrashBandicootExtractor extends ProgressMonitor {
    private static final int TOTAL_STEPS = 8;
    private File mApkFile;
    private File mZpakData;
    private File mOutputDir;
    private static final int BLOCK_SIZE = 1024;
    private static final Map<String, String> STRING_REPLACEMENT_MAP = new TreeMap<String, String>();

    public CrashBandicootExtractor(File apk, File zPakData, File outputDir) {
        this.mApkFile = apk;
        this.mOutputDir = outputDir;
        this.mZpakData = zPakData;
        setTotalSteps(TOTAL_STEPS);
    }

    public void extractApk() throws IOException, URISyntaxException {
        Logger.info("Starting extraction with PSXPeria Extractor version %s", PSXperiaTool.VERSION);
        verifyFiles();
        processConfig();
        decodeValues();
        FileFilter filterCompiledRes = new FileFilter() {
            public boolean accept(File file) {
                if(file.getParent() == null || file.getParentFile().getParent() == null)
                    return true;
                File parent = file.getParentFile();
                return (!parent.getName().equals("res")) && (!parent.getParentFile().getName().equals("res"));
            }
        };
        nextStep("Extracting APK");
        extractZip(mApkFile, mOutputDir, filterCompiledRes);
        extractZpaks();
        cleanUp();
        moveResourceFiles();
        patchStrings();
        patchEmulator();
        nextStep("Done.");
    }

    private void verifyFiles() throws IOException {
        nextStep("Verifying files");
        if (!mApkFile.exists())
            throw new FileNotFoundException("Cannot find APK file: " + mApkFile.getPath());
        if (!mZpakData.exists())
            throw new FileNotFoundException("Cannot find ZPAK file: " + mZpakData.getPath());
        if (!mOutputDir.exists())
            mOutputDir.mkdirs();
        if(mOutputDir.list().length > 0)
            Logger.warning("The output directory is not empty! Whatever is in this folder will be included in all generated APKs.");
        //FileUtils.cleanDirectory(mOutputDir);
    }

    private void processConfig() throws IOException, UnsupportedOperationException {
        long crc32 = ZpakCreate.getCRC32(mApkFile);
        String crcString = Long.toHexString(crc32).toUpperCase();
        InputStream inConfig = null;
        if((inConfig = PSXperiaTool.class.getResourceAsStream("/resources/patches/" + crcString + "/config.xml")) == null){
            throw new FileNotFoundException("Cannot find config for this APK (CRC32: " + crcString + ")");
        }
        Properties config = new Properties();
        config.loadFromXML(inConfig);
        inConfig.close();
        Logger.info(
                "Identified " + config.getProperty("game_name", "Unknown Game") +
                        " " + config.getProperty("game_region") +
                        " Version " + config.getProperty("game_version", "Unknown") +
                        ", CRC32: " + config.getProperty("game_crc32", "Unknown")
        );
        if(config.getProperty("valid", "yes").equals("no"))
            throw new UnsupportedOperationException("This APK is not supported.");
        Logger.verbose("Copying config files.");
        FileUtils.copyInputStreamToFile(PSXperiaTool.class.getResourceAsStream("/resources/patches/" + crcString + "/config.xml"), new File(mOutputDir, "/config/config.xml"));
        FileUtils.copyInputStreamToFile(PSXperiaTool.class.getResourceAsStream("/resources/patches/" + crcString + "/filelist.txt"), new File(mOutputDir, "/config/filelist.txt"));
        FileUtils.copyInputStreamToFile(PSXperiaTool.class.getResourceAsStream("/resources/patches/" + crcString + "/stringReplacements.txt"), new File(mOutputDir, "/config/stringReplacements.txt"));
        String emulatorPatch = config.getProperty("emulator_patch", "");
        String gamePatch = config.getProperty("iso_patch", "");
        if(!gamePatch.equals("")){
            FileUtils.copyInputStreamToFile(PSXperiaTool.class.getResourceAsStream("/resources/patches/" + crcString + "/" + gamePatch), new File(mOutputDir, "/config/game-patch.bin"));
        }
        if(!emulatorPatch.equals("")){
            FileUtils.copyInputStreamToFile(PSXperiaTool.class.getResourceAsStream("/resources/patches/" + crcString + "/" + emulatorPatch), new File(mOutputDir, "/config/" + emulatorPatch));
        }
    }

    private void extractZip(File zipFile, File output, FileFilter filter) throws IOException {
        Logger.info("Extracting ZIP file: %s to: %s", zipFile.getPath(), output.getPath());
        if(!output.exists())
            output.mkdirs();
        ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile));
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            File file = new File(output, entry.getName());
            if (file.isDirectory())
                continue;
            if(filter != null && !filter.accept(file))
                continue;
            Logger.verbose("Unzipping %s", entry.getName());
            FileUtils.touch(file);
            FileOutputStream out = new FileOutputStream(file.getPath());
            int n;
            byte[] buffer = new byte[BLOCK_SIZE];
            while ((n = zip.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.close();
            zip.closeEntry();
            Logger.verbose("Done extracting %s", entry.getName());
        }
        zip.close();
        Logger.debug("Done extracting ZIP.");
    }

    private void extractZpaks() throws IOException {
        nextStep("Extracting ZPAKS");
        WildcardFileFilter ff = new WildcardFileFilter("*.zpak");
        File[] candidates = (new File(mOutputDir, "/assets")).listFiles((FileFilter)ff);
        if(candidates == null || candidates.length < 1)
            throw new FileNotFoundException("Cannot find the default ZPAK under /assets");
        else if(candidates.length > 1)
            Logger.warning("Found more than one default ZPAK under /assets. Using the first one.");
        File defaultZpak = candidates[0];
        extractZip(defaultZpak, new File(mOutputDir, "/assets/ZPAK"), null);
        FileFilter filter = new FileFilter() {
            public boolean accept(File file) {
                if(file.getName().equals("image.ps"))
                    return false;
                if(file.getParentFile() == null)
                    return true;
                if(file.getParentFile().getName().equals("manual"))
                    return false;
                return true;
            }
        };
        extractZip(mZpakData, new File(mOutputDir, "/ZPAK"), filter);
        defaultZpak.delete();
    }

    private void decodeValues() throws IOException {
        nextStep("Decoding values");
        try {
            AndrolibResources res = new AndrolibResources();
            ExtFile extFile = new ExtFile(mApkFile);
            ResTable resTable = res.getResTable(extFile);
            res.decode(resTable, extFile, mOutputDir);
        } catch (AndrolibException ex) {
            ex.printStackTrace();
            throw new IOException(ex);
        }
    }

    private void cleanUp() throws IOException {
        nextStep("Removing unneeded files.");
        (new File(mOutputDir, "/AndroidManifest.xml")).delete();
        FileUtils.deleteDirectory(new File(mOutputDir, "/META-INF"));
        Logger.verbose("Done cleaning up.");
    }

    private void moveResourceFiles() throws IOException {
        nextStep("Adding new files.");
        InputStream defaultIcon = PSXperiaTool.class.getResourceAsStream("/resources/icon.png");
        writeStreamToFile(defaultIcon, new File(mOutputDir, "/assets/ZPAK/assets/default/bitmaps/icon.png"));
        defaultIcon = PSXperiaTool.class.getResourceAsStream("/resources/icon.png");
        writeStreamToFile(defaultIcon, new File(mOutputDir, "/res/drawable/icon.png"));
        defaultIcon.close();
        Logger.verbose("Done adding new files.");
    }

    private void writeStreamToFile(InputStream in, File outFile) throws IOException {
        Logger.verbose("Writing to: %s", outFile.getPath());
        FileOutputStream out = new FileOutputStream(outFile);
        byte[] buffer = new byte[BLOCK_SIZE];
        int n;
        while((n = in.read(buffer)) != -1){
            out.write(buffer, 0, n);
        }
        out.close();
    }

    private void fillReplacementMap() throws IOException {
        Logger.verbose("Filling string replacement map with resource data.");
        File file = new File(mOutputDir, "/config/stringReplacements.txt");
        InputStream in = new FileInputStream(file);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line1, line2;
        while((line1 = reader.readLine()) != null && (line2 = reader.readLine()) != null){
            if(line1.isEmpty())
                continue;
            Logger.verbose("Replacing %s with %s.", line1, line2);
            STRING_REPLACEMENT_MAP.put(line1, line2);
        }
        reader.close();
        in.close();
        file.delete();
    }

    private void patchStrings() throws IOException {
        nextStep("Patching XML strings with tags.");
        if(STRING_REPLACEMENT_MAP.isEmpty()){
            fillReplacementMap();
        }
        StringReplacement strReplace = new StringReplacement(STRING_REPLACEMENT_MAP, mOutputDir);
        strReplace.execute(PSXperiaTool.FILES_TO_MODIFY);
        Logger.verbose("String replacement done.");
    }

    private void patchEmulator() throws IOException {
        Logger.info("Verifying the emulator binary.");
        Properties config = new Properties();
        config.loadFromXML(new FileInputStream(new File(mOutputDir, "/config/config.xml")));
        String emulatorName = config.getProperty("emulator_name", "libjava-activity.so");
        File origEmulator = new File(mOutputDir, "/lib/armeabi/" + emulatorName);
        String emulatorCRC32 = Long.toHexString(FileUtils.checksumCRC32(origEmulator));
        if(!emulatorCRC32.equalsIgnoreCase(config.getProperty("emulator_crc32")))
            throw new UnsupportedOperationException("The emulator checksum is invalid. Cannot patch. CRC32: " + emulatorCRC32);
        File newEmulator = new File(mOutputDir, "/lib/armeabi/libjava-activity-patched.so");
        File emulatorPatch = new File(mOutputDir, "/config/" + config.getProperty("emulator_patch", ""));
        if(emulatorPatch.equals("")){
            Logger.info("No patch needed.");
            FileUtils.moveFile(origEmulator, newEmulator);
        }else{
            Logger.info("Patching emulator.");
            newEmulator.createNewFile();
            JBPatch.bspatch(origEmulator, newEmulator, emulatorPatch);
            emulatorPatch.delete();
        }
        FileUtils.copyInputStreamToFile(PSXperiaTool.class.getResourceAsStream("/resources/libjava-activity-wrapper.so"), origEmulator);
    }
}
