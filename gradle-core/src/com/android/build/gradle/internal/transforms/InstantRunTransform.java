/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.transforms;

import static com.android.build.api.transform.QualifiedContent.DefaultContentType;
import static com.android.build.api.transform.QualifiedContent.Scope;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.incremental.IncrementalChangeVisitor;
import com.android.build.gradle.internal.incremental.IncrementalSupportVisitor;
import com.android.build.gradle.internal.incremental.IncrementalVisitor;
import com.android.build.gradle.internal.incremental.InstantRunBuildContext;
import com.android.build.gradle.internal.pipeline.ExtendedContentType;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.ide.common.util.UrlClassLoaderUtil;
import com.android.utils.FileUtils;
import com.android.utils.ILogger;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import org.gradle.api.logging.Logging;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of the {@link Transform} to run the byte code enhancement logic on compiled
 * classes in order to support runtime hot swapping.
 */
public class InstantRunTransform extends Transform {

    protected static final ILogger LOGGER =
            new LoggerWrapper(Logging.getLogger(InstantRunTransform.class));
    private final ChangeRecords generatedClasses2Files = new ChangeRecords();
    private final ChangeRecords generatedClasses3Files = new ChangeRecords();
    private final ImmutableList.Builder<String> generatedClasses3Names = ImmutableList.builder();
    private final VariantScope variantScope;

    public InstantRunTransform(VariantScope variantScope) {
        this.variantScope = variantScope;
    }

    enum RecordingPolicy {RECORD, DO_NOT_RECORD}

    @NonNull
    @Override
    public String getName() {
        return "instantRun";
    }

    @NonNull
    @Override
    public Set<ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @NonNull
    @Override
    public Set<ContentType> getOutputTypes() {
        return ImmutableSet.<ContentType>of(
                DefaultContentType.CLASSES,
                ExtendedContentType.CLASSES_ENHANCED);
    }

    @NonNull
    @Override
    public Set<QualifiedContent.Scope> getScopes() {
        return Sets.immutableEnumSet(Scope.PROJECT, Scope.SUB_PROJECTS);
    }

    @NonNull
    @Override
    public Set<Scope> getReferencedScopes() {
        return Sets.immutableEnumSet(Scope.EXTERNAL_LIBRARIES,
                Scope.PROJECT_LOCAL_DEPS,
                Scope.SUB_PROJECTS_LOCAL_DEPS,
                Scope.PROVIDED_ONLY);
    }

    @Override
    public boolean isIncremental() {
        return true;
    }

    @Override
    public void transform(@NonNull TransformInvocation invocation)
            throws IOException, TransformException, InterruptedException {

        TransformOutputProvider outputProvider = invocation.getOutputProvider();
        if (outputProvider == null) {
            throw new IllegalStateException("InstantRunTransform called with null output");
        }

        // first get all referenced input to construct a class loader capable of loading those
        // classes. This is useful for ASM as it needs to load classes
        List<URL> referencedInputUrls = getAllClassesLocations(
                invocation.getInputs(), invocation.getReferencedInputs());

        // This class loader could be optimized a bit, first we could create a parent class loader
        // with the android.jar only that could be stored in the GlobalScope for reuse. This
        // class loader could also be store in the VariantScope for potential reuse if some
        // other transform need to load project's classes.
        URLClassLoader urlClassLoader = new URLClassLoader(
                referencedInputUrls.toArray(new URL[referencedInputUrls.size()]), null) {
            @Override
            public URL getResource(String name) {
                // Never delegate to bootstrap classes.
                return findResource(name);
            }
        };

        ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            variantScope.getInstantRunBuildContext().startRecording(
                    InstantRunBuildContext.TaskType.INSTANT_RUN_TRANSFORM);
            Thread.currentThread().setContextClassLoader(urlClassLoader);

            File classesTwoOutput = outputProvider.getContentLocation("main",
                    TransformManager.CONTENT_CLASS, getScopes(), Format.DIRECTORY);

            File classesThreeOutput = outputProvider.getContentLocation("enhanced",
                    ImmutableSet.<ContentType>of(ExtendedContentType.CLASSES_ENHANCED),
                    getScopes(), Format.DIRECTORY);

            for (TransformInput input : invocation.getInputs()) {
                for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                    File inputDir = directoryInput.getFile();
                    if (invocation.isIncremental()) {
                        for (Map.Entry<File, Status> fileEntry : directoryInput.getChangedFiles()
                                .entrySet()) {

                            File inputFile = fileEntry.getKey();
                            if (!inputFile.getName().endsWith(SdkConstants.DOT_CLASS))
                                continue;
                            switch (fileEntry.getValue()) {
                                case ADDED:
                                    // a new file was added, we only generate the classes.2 format
                                    transformToClasses2Format(
                                            inputDir,
                                            inputFile,
                                            classesTwoOutput,
                                            Status.ADDED,
                                            RecordingPolicy.RECORD);
                                    break;
                                case REMOVED:
                                    // remove the classes.2 and classes.3 files.
                                    File outputFile = deleteOutputFile(
                                            IncrementalSupportVisitor.VISITOR_BUILDER,
                                            inputDir, inputFile, classesTwoOutput);
                                    if (outputFile != null) {
                                        generatedClasses2Files.add(
                                                Status.REMOVED, outputFile.getAbsolutePath());
                                    }
                                    deleteOutputFile(IncrementalChangeVisitor.VISITOR_BUILDER,
                                            inputDir, inputFile, classesThreeOutput);
                                    break;
                                case CHANGED:
                                    // an existing file was changed, we regenerate the classes.2
                                    // and classes.3 files as they are both needed to support
                                    // restart and reload.
                                    transformToClasses2Format(
                                            inputDir,
                                            inputFile,
                                            classesTwoOutput,
                                            Status.CHANGED,
                                            RecordingPolicy.RECORD);
                                    transformToClasses3Format(
                                            inputDir,
                                            inputFile,
                                            classesThreeOutput);
                                    break;
                                case NOTCHANGED:
                                    break;
                                default:
                                    throw new IllegalStateException("Unhandled file status "
                                            + fileEntry.getValue());
                            }
                        }
                    } else {
                        // non incremental mode, we need to traverse the TransformInput#getFiles()
                        // folder
                        for (File file : Files.fileTreeTraverser().breadthFirstTraversal(inputDir)) {
                            if (file.isDirectory()) {
                                continue;
                            }

                            try {
                                // do not record the changes, everything should be packaged in the
                                // main APK.
                                transformToClasses2Format(
                                        inputDir,
                                        file,
                                        classesTwoOutput,
                                        Status.ADDED,
                                        RecordingPolicy.DO_NOT_RECORD);
                            } catch (IOException e) {
                                throw new RuntimeException("Exception while preparing "
                                        + file.getAbsolutePath());
                            }
                        }
                        File incremental =
                                InstantRunBuildType.RESTART.getIncrementalChangesFile(variantScope);
                        if (incremental.exists() && !incremental.delete()) {
                            LOGGER.warning("Cannot delete " + incremental);
                        }
                    }

                }
            }

            wrapUpOutputs(classesTwoOutput, classesThreeOutput);
        } finally {
            Thread.currentThread().setContextClassLoader(currentClassLoader);
            UrlClassLoaderUtil.attemptToClose(urlClassLoader);
            variantScope.getInstantRunBuildContext().stopRecording(
                    InstantRunBuildContext.TaskType.INSTANT_RUN_TRANSFORM);
        }
    }

    protected void wrapUpOutputs(File classes2Folder, File classes3Folder)
            throws IOException {

        // generate the patch file and add to the list of files to process next.
        ImmutableList<String> generatedClassNames = generatedClasses3Names.build();
        if (!generatedClassNames.isEmpty()) {
            File patchFile = writePatchFileContents(generatedClassNames, classes3Folder);
            generatedClasses3Files.add(Status.CHANGED, patchFile.getAbsolutePath());
        }

        // read the previous iterations output and append it to this iteration changes.
        File incremental = InstantRunBuildType.RESTART.getIncrementalChangesFile(variantScope);
        if (incremental.exists()) {
            ChangeRecords previousIterationChanges = ChangeRecords.load(incremental);
            merge(generatedClasses2Files, previousIterationChanges);
        }

        generatedClasses2Files.write(
                InstantRunBuildType.RESTART.getIncrementalChangesFile(variantScope));

        generatedClasses3Files.write(
                InstantRunBuildType.RELOAD.getIncrementalChangesFile(variantScope));
    }

    /**
     * Merges a past iteration set of change records into this iteration following simple merging
     * rules :
     *    - if the file has been deleted during this iteration, do not merge past records.
     *    - if the file had been deleted previously but re-added in this iteration, remove the
     *      delete event.
     * @param changeRecords this iteration change records
     * @param pastIterationRecords past iteration change records.
     */
    @VisibleForTesting
    static void merge(@NonNull ChangeRecords changeRecords,
            @NonNull ChangeRecords pastIterationRecords) {

        Set<String> deletedFiles = changeRecords.getFilesForStatus(Status.REMOVED);
        for (Map.Entry<String, Status> record : pastIterationRecords.records.entrySet()) {
            // if a previous iteration removed a class, only keep that remove event if it
            // was not re-added during this iteration.
            if (record.getValue() == Status.REMOVED) {
                if (changeRecords.getChangeFor(record.getKey()) == null) {
                    changeRecords.add(record.getValue(), record.getKey());
                }
            } else {
                // do not keep previous iteration ADD/CHANGED records for files that got deleted
                // during this iteration.
                if (!deletedFiles.contains(record.getKey())) {
                    changeRecords.add(record.getValue(), record.getKey());
                }
            }
        }

        // remove all the previous iteration REMOVED records if the class is re-added.
    }

    /**
     * Calculate a list of {@link URL} that represent all the directories containing classes
     * either directly belonging to this project or referencing it.
     *
     * @param inputs the project's inputs
     * @param referencedInputs the project's referenced inputs
     * @return a {@link List} or {@link URL} for all the locations.
     * @throws MalformedURLException if once the locatio
     */
    @NonNull
    private List<URL> getAllClassesLocations(
            @NonNull Collection<TransformInput> inputs,
            @NonNull Collection<TransformInput> referencedInputs) throws MalformedURLException {

        List<URL> referencedInputUrls = new ArrayList<URL>();

        // add the bootstrap classpath for jars like android.jar
        for (File file : variantScope.getGlobalScope().getAndroidBuilder().getBootClasspath(
                true /* includeOptionalLibraries */)) {
            referencedInputUrls.add(file.toURI().toURL());
        }

        // now add the project dependencies.
        for (TransformInput referencedInput : referencedInputs) {
            addAllClassLocations(referencedInput, referencedInputUrls);
        }

        // and finally add input folders.
        for (TransformInput input : inputs) {
            addAllClassLocations(input, referencedInputUrls);
        }
        return referencedInputUrls;
    }

    private static void addAllClassLocations(TransformInput transformInput, List<URL> into)
            throws MalformedURLException {

        for (DirectoryInput directoryInput : transformInput.getDirectoryInputs()) {
            into.add(directoryInput.getFile().toURI().toURL());
        }
        for (JarInput jarInput : transformInput.getJarInputs()) {
            into.add(jarInput.getFile().toURI().toURL());
        }
    }

    /**
     * Transform a single file into a format supporting class hot swap.
     *
     * @param inputDir the input directory containing the input file.
     * @param inputFile the input file within the input directory to transform.
     * @param outputDir the output directory where to place the transformed file.
     * @param change the nature of the change that triggered the transformation.
     * @param recordingPolicy whether or not, we should record the event.
     * @throws IOException if the transformation failed.
     */
    protected void transformToClasses2Format(
            @NonNull final File inputDir,
            @NonNull final File inputFile,
            @NonNull final File outputDir,
            @NonNull final Status change,
            @NonNull final RecordingPolicy recordingPolicy)
            throws IOException {
        if (inputFile.getPath().endsWith(SdkConstants.DOT_CLASS)) {
            File outputFile = IncrementalVisitor.instrumentClass(
                    inputDir, inputFile, outputDir, IncrementalSupportVisitor.VISITOR_BUILDER);

            if (outputFile != null && recordingPolicy == RecordingPolicy.RECORD) {
                generatedClasses2Files.add(change, outputFile.getAbsolutePath());
            }
        }
    }

    @Nullable
    private static File deleteOutputFile(
            @NonNull IncrementalVisitor.VisitorBuilder visitorBuilder,
            @NonNull File inputDir, @NonNull File inputFile, @NonNull File outputDir) {
        String inputPath = FileUtils.relativePossiblyNonExistingPath(inputFile, inputDir);
        String outputPath =
                visitorBuilder.getMangledRelativeClassFilePath(inputPath);
        File outputFile = new File(outputDir, outputPath);
        if (outputFile.exists()) {
            try {
                FileUtils.delete(outputFile);
            } catch (IOException e) {
                // it's not a big deal if the file cannot be deleted, hopefully
                // no code is still referencing it, yet we should notify.
                LOGGER.warning("Cannot delete %1$s file.\nCause: %2$s",
                        outputFile, Throwables.getStackTraceAsString(e));
            }
            return outputFile;
        }
        return null;
    }

    /**
     * Transform a single file into a {@link ExtendedContentType#CLASSES_ENHANCED} format
     *
     * @param inputDir the input directory containing the input file.
     * @param inputFile the input file within the input directory to transform.
     * @param outputDir the output directory where to place the transformed file.
     * @throws IOException if the transformation failed.
     */
    protected void transformToClasses3Format(File inputDir, File inputFile, File outputDir)
            throws IOException {

        File outputFile = IncrementalVisitor.instrumentClass(
                inputDir, inputFile, outputDir, IncrementalChangeVisitor.VISITOR_BUILDER);

        // if the visitor returned null, that means the class not be hot swapped or more likely
        // that it was disabled for InstantRun, we don't add it to our collection of generated
        // classes and it will not be part of the Patch class that apply changes.
        if (outputFile == null) {
            return;
        }
        generatedClasses3Names.add(
                inputFile.getAbsolutePath().substring(
                    inputDir.getAbsolutePath().length() + 1,
                    inputFile.getAbsolutePath().length() - ".class".length())
                        .replace(File.separatorChar, '.'));
        generatedClasses3Files.add(Status.CHANGED, outputFile.getAbsolutePath());
    }

    /**
     * Use asm to generate a concrete subclass of the AppPathLoaderImpl class.
     * It only implements one method :
     *      String[] getPatchedClasses();
     *
     * The method is supposed to return the list of classes that were patched in this iteration.
     * This will be used by the InstantRun runtime to load all patched classes and register them
     * as overrides on the original classes.2 class files.
     *
     * @param patchFileContents list of patched class names.
     * @param outputDir output directory where to generate the .class file in.
     * @return the generated .class files
     */
    private static File writePatchFileContents(
            ImmutableList<String> patchFileContents, File outputDir) {

        ClassWriter cw = new ClassWriter(0);
        MethodVisitor mv;

        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                IncrementalVisitor.APP_PATCHES_LOADER_IMPL, null,
                IncrementalVisitor.ABSTRACT_PATCHES_LOADER_IMPL, null);

        {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    IncrementalVisitor.ABSTRACT_PATCHES_LOADER_IMPL,
                    "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
                    "getPatchedClasses", "()[Ljava/lang/String;", null, null);
            mv.visitCode();
            mv.visitIntInsn(Opcodes.BIPUSH, patchFileContents.size());
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
            for (int index=0; index < patchFileContents.size(); index++) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitIntInsn(Opcodes.BIPUSH, index);
                mv.visitLdcInsn(patchFileContents.get(index));
                mv.visitInsn(Opcodes.AASTORE);
            }
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(4, 1);
            mv.visitEnd();
        }
        cw.visitEnd();

        byte[] classBytes = cw.toByteArray();
        File outputFile = new File(outputDir, IncrementalVisitor.APP_PATCHES_LOADER_IMPL + ".class");
        try {
            Files.createParentDirs(outputFile);
            Files.write(classBytes, outputFile);
            // add the files to the list of files to be processed by subsequent tasks.
            return outputFile;
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
