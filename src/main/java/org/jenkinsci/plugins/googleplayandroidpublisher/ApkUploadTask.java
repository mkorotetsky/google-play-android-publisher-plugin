package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.services.androidpublisher.model.Apk;
import com.google.api.services.androidpublisher.model.ExpansionFile;
import com.google.api.services.androidpublisher.model.ExpansionFilesUploadResponse;
import com.google.api.services.androidpublisher.model.LocalizedText;
import com.google.api.services.androidpublisher.model.TrackRelease;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import hudson.FilePath;
import hudson.model.TaskListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.apache.commons.codec.digest.DigestUtils;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.ExpansionFileSet;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ApkPublisher.RecentChanges;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.DEOBFUSCATION_FILE_TYPE_PROGUARD;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.OBB_FILE_TYPE_MAIN;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Constants.OBB_FILE_TYPE_PATCH;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getApkMetadata;

class ApkUploadTask extends TrackPublisherTask<Boolean> {

    private final FilePath workspace;
    private final List<FilePath> apkFiles;
    private final Map<FilePath, FilePath> apkFilesToMappingFiles;
    private final Map<Integer, ExpansionFileSet> expansionFiles;
    private final boolean usePreviousExpansionFilesIfMissing;
    private final RecentChanges[] recentChangeList;
    private final List<Integer> existingVersionCodes;
    private int latestMainExpansionFileVersionCode;
    private int latestPatchExpansionFileVersionCode;

    ApkUploadTask(TaskListener listener, GoogleRobotCredentials credentials, String applicationId,
                  FilePath workspace, List<FilePath> apkFiles, Map<FilePath, FilePath> apkFilesToMappingFiles,
                  Map<Integer, ExpansionFileSet> expansionFiles, boolean usePreviousExpansionFilesIfMissing,
                  ReleaseTrack track, double rolloutPercentage, ApkPublisher.RecentChanges[] recentChangeList) {
        super(listener, credentials, applicationId, track, rolloutPercentage);
        this.workspace = workspace;
        this.apkFiles = apkFiles;
        this.apkFilesToMappingFiles = apkFilesToMappingFiles;
        this.expansionFiles = expansionFiles;
        this.usePreviousExpansionFilesIfMissing = usePreviousExpansionFilesIfMissing;
        this.recentChangeList = recentChangeList;
        this.existingVersionCodes = new ArrayList<Integer>();
    }

    protected Boolean execute() throws IOException, InterruptedException {
        // Open an edit via the Google Play API, thereby ensuring that our credentials etc. are working
        logger.println(String.format("Authenticating to Google Play API...%n" +
                        "- Credential:     %s%n" +
                        "- Application ID: %s%n", getCredentialName(), applicationId));
        createEdit(applicationId);

        // Get the list of existing APKs and their info
        List<Apk> existingApks = editService.apks().list(applicationId, editId).execute().getApks();
        if (existingApks == null) existingApks = Collections.emptyList();
        for (Apk apk : existingApks) {
            existingVersionCodes.add(apk.getVersionCode());
        }

        if(expansionFiles != null)
        {
            existingVersionCodes.addAll(expansionFiles.keySet());
        }

        // Upload each of the APKs
        logger.println(String.format("Uploading %d APK(s) with application ID: %s%n", apkFiles.size(), applicationId));
        final ArrayList<Integer> uploadedVersionCodes = new ArrayList<>();
        for (FilePath apkFile : apkFiles) {
            final ApkMeta metadata = getApkMetadata(new File(apkFile.getRemote()));
            final String apkSha1Hash = getSha1Hash(apkFile.getRemote());

            // Log some useful information about the file that will be uploaded
            logger.println(String.format("      APK file: %s", getRelativeFileName(apkFile)));
            logger.println(String.format("    SHA-1 hash: %s", apkSha1Hash));
            logger.println(String.format("   versionCode: %d", metadata.getVersionCode()));
            logger.println(String.format(" minSdkVersion: %s", metadata.getMinSdkVersion()));

            // Check whether this APK already exists on the server (i.e. uploading it would fail)
            for (Apk apk : existingApks) {
                if (apk.getBinary().getSha1().toLowerCase(Locale.ENGLISH).equals(apkSha1Hash)) {
                    logger.println();
                    logger.println("This APK already exists in the Google Play account; it cannot be uploaded again");
                    return false;
                }
            }

            // If not, we can upload the file
            FileContent apk =
                    new FileContent("application/vnd.android.package-archive", new File(apkFile.getRemote()));
            Apk uploadedApk = editService.apks().upload(applicationId, editId, apk).execute();
            uploadedVersionCodes.add(uploadedApk.getVersionCode());

            // Upload the ProGuard mapping file for this APK, if there is one
            final FilePath mappingFile = apkFilesToMappingFiles.get(apkFile);
            if (mappingFile != null) {
                final String relativeFileName = getRelativeFileName(mappingFile);

                // Google Play API doesn't accept empty mapping files
                logger.println(String.format(" Mapping file size: %s", mappingFile.length()));
                if (mappingFile.length() == 0) {
                    logger.println(String.format(" Ignoring empty ProGuard mapping file: %s", relativeFileName));
                } else {
                    logger.println(String.format(" Uploading associated ProGuard mapping file: %s", relativeFileName));
                    FileContent mapping =
                            new FileContent("application/octet-stream", new File(mappingFile.getRemote()));
                    editService.deobfuscationfiles().upload(applicationId, editId, uploadedApk.getVersionCode(),
                            DEOBFUSCATION_FILE_TYPE_PROGUARD, mapping).execute();
                }
            }
            logger.println("");
        }

        // Upload the expansion files, or associate the previous ones, if configured
        if (!expansionFiles.isEmpty() || usePreviousExpansionFilesIfMissing) {
            for (int versionCode : uploadedVersionCodes) {
                ExpansionFileSet fileSet = expansionFiles.get(versionCode);
                FilePath mainFile = fileSet == null ? null : fileSet.getMainFile();
                FilePath patchFile = fileSet == null ? null : fileSet.getPatchFile();

                logger.println(String.format("Handling expansion files for versionCode %d", versionCode));
                applyExpansionFile(versionCode, OBB_FILE_TYPE_MAIN, mainFile, usePreviousExpansionFilesIfMissing);
                applyExpansionFile(versionCode, OBB_FILE_TYPE_PATCH, patchFile, usePreviousExpansionFilesIfMissing);
                logger.println();
            }
        }

        // Assign all uploaded APKs to the configured track
        List<LocalizedText> releaseNotes = Util.transformReleaseNotes(recentChangeList);
        TrackRelease release = Util.buildRelease(uploadedVersionCodes, rolloutFraction, releaseNotes);
        assignApksToTrack(track, rolloutFraction, release);

        // Commit all the changes
        try {
            logger.println("Applying changes to Google Play...");
            editService.commit(applicationId, editId).execute();
        } catch (SocketTimeoutException e) {
            // The API is quite prone to timing out for no apparent reason,
            // despite having successfully committed the changes on the backend.
            // So here we check whether the APKs uploaded were actually committed
            logger.println(String.format("- An error occurred while applying changes: %s", e));
            logger.println("- Checking whether the changes have been applied anyway...\n");
            if (!wereApksUploaded(uploadedVersionCodes)) {
                logger.println("The APKs that were uploaded were not found on Google Play");
                logger.println("- No changes have been applied to the Google Play account");
                return false;
            }
        }

        // If committing didn't throw an exception, everything worked fine
        logger.println("Changes were successfully applied to Google Play");
        return true;
    }

    /** Applies an expansion file to an APK, whether from a given file, or by using previously-uploaded file.  */
    private void applyExpansionFile(int versionCode, String type, FilePath filePath, boolean usePreviousIfMissing)
            throws IOException {
        // If there was a file provided, simply upload it
        if (filePath != null) {
            logger.println(String.format("- Uploading new %s expansion file: %s", type, filePath.getName()));
            uploadExpansionFile(versionCode, type, filePath);
            return;
        }

        // Otherwise, check whether we should reuse an existing expansion file
        if (usePreviousIfMissing) {
            // Ensure we know what the latest expansion files versions are
            fetchLatestExpansionFileVersionCodes();

            // If there is no previous APK with this type of expansion file, there's nothing we can do
            final int latestVersionCodeWithExpansion = type.equals(OBB_FILE_TYPE_MAIN) ?
                    latestMainExpansionFileVersionCode : latestPatchExpansionFileVersionCode;
            if (latestVersionCodeWithExpansion == -1) {
                logger.println(String.format("- No %1$s expansion file to apply, and no existing APK with a %1$s " +
                        "expansion file was found", type));
                return;
            }

            // Otherwise, associate the latest expansion file of this type with the new APK
            logger.println(String.format("- Applying %s expansion file from previous APK: %d", type,
                    latestVersionCodeWithExpansion));
            ExpansionFile fileRef = new ExpansionFile().setReferencesVersion(latestVersionCodeWithExpansion);
            editService.expansionfiles().update(applicationId, editId, versionCode, type, fileRef).execute();
            return;
        }

        // If we don't want to reuse an existing file, then there's nothing to do
        logger.println(String.format("- No %s expansion file to apply", type));
    }

    /** Determines whether there are already-existing APKs for this app which have expansion files associated. */
    private void fetchLatestExpansionFileVersionCodes() throws IOException {
        // Don't do this again if we've already attempted to find the expansion files
        if (latestMainExpansionFileVersionCode != 0 && latestPatchExpansionFileVersionCode != 0) {
            return;
        }

        // Sort the existing APKs so that the newest come first
        Collections.sort(existingVersionCodes);
        Collections.reverse(existingVersionCodes);

        // Find the latest APK with a main expansion file, and the latest with a patch expansion file
        latestMainExpansionFileVersionCode = fetchLatestExpansionFileVersionCode(OBB_FILE_TYPE_MAIN);
        latestPatchExpansionFileVersionCode = fetchLatestExpansionFileVersionCode(OBB_FILE_TYPE_PATCH);
    }

    /** @return The version code of the newest APK which has an expansion file of this type, else {@code -1}. */
    private int fetchLatestExpansionFileVersionCode(String type) throws IOException {
        // Find the latest APK with a patch expansion file
        for (int versionCode : existingVersionCodes) {
            ExpansionFile file = getExpansionFile(versionCode, type);
            if (file == null) {
                continue;
            }
            if (file.getFileSize() != null && file.getFileSize() > 0) {
                return versionCode;
            }
            if (file.getReferencesVersion() != null && file.getReferencesVersion() > 0) {
                return file.getReferencesVersion();
            }
        }

        // There's no existing expansion file of this type
        return -1;
    }

    /** @return The expansion file API info for the given criteria, or {@code null} if no such file exists. */
    private ExpansionFile getExpansionFile(int versionCode, String type) throws IOException {
        try {
            return editService.expansionfiles().get(applicationId, editId, versionCode, type).execute();
        } catch (GoogleJsonResponseException e) {
            // A 404 response from the API means that there is no such expansion file/reference
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Uploads the given file as an certain type expansion file, associating it with a given APK.
     *
     * @return The expansion file API response.
     */
    private ExpansionFilesUploadResponse uploadExpansionFile(int versionCode, String type, FilePath filePath)
            throws IOException {
        FileContent file = new FileContent("application/octet-stream", new File(filePath.getRemote()));
        return editService.expansionfiles().upload(applicationId, editId, versionCode, type, file).execute();
    }

    /**
     * Starts a new API session and determines whether a list of version codes were successfully uploaded.
     *
     * @param uploadedVersionCodes The list to be checked for existence.
     * @return {@code true} if APK version codes in the list were found to now exist on Google Play.
     */
    private boolean wereApksUploaded(Collection<Integer> uploadedVersionCodes) throws IOException {
        // Last edit is finished; create a new one to get the current state
        createEdit(applicationId);

        // Get the current list of version codes
        List<Integer> currentVersionCodes = new ArrayList<>();
        List<Apk> currentApks = editService.apks().list(applicationId, editId).execute().getApks();
        if (currentApks == null) currentApks = Collections.emptyList();
        for (Apk apk : currentApks) {
            currentVersionCodes.add(apk.getVersionCode());
        }

        // The upload succeeded if the current list of version codes intersects with the list we tried to upload
        return uploadedVersionCodes.removeAll(currentVersionCodes);
    }

    /** @return The path to the given file, relative to the build workspace. */
    private String getRelativeFileName(FilePath file) {
        final String ws = workspace.getRemote();
        String path = file.getRemote();
        if (path.startsWith(ws) && path.length() > ws.length()) {
            path = path.substring(ws.length());
        }
        if (path.charAt(0) == File.separatorChar && path.length() > 1) {
            path = path.substring(1);
        }
        return path;
    }

    /** @return The SHA-1 hash of the given file, as a lower-case hex string. */
    private static String getSha1Hash(String path) throws IOException {
        FileInputStream fis = new FileInputStream(path);
        try {
            return DigestUtils.sha1Hex(fis).toLowerCase(Locale.ENGLISH);
        } finally {
            fis.close();
        }
    }

}
