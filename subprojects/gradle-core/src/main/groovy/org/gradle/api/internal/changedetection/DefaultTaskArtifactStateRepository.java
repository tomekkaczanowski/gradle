/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.internal.changedetection;

import org.gradle.api.UncheckedIOException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.internal.TaskInternal;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.PersistentCache;
import org.gradle.util.HashUtil;

import java.io.*;
import java.util.*;

public class DefaultTaskArtifactStateRepository implements TaskArtifactStateRepository {
    private static Logger logger = Logging.getLogger(DefaultTaskArtifactStateRepository.class);
    private final CacheRepository repository;
    private Map<File, OutputGenerators> outputs;
    private PersistentCache cache;

    public DefaultTaskArtifactStateRepository(CacheRepository repository) {
        this.repository = repository;
    }

    public TaskArtifactState getStateFor(final TaskInternal task) {
        if (outputs == null) {
            loadTasks(task);
        }

        final TaskKey key = new TaskKey(task);
        final TaskInfo thisExecution = getThisExecution(task);
        final TaskExecution lastExecution = getLastExecution(key, thisExecution);

        return new TaskArtifactState() {
            public boolean isUpToDate() {
                List<String> messages = thisExecution.isSameAs(lastExecution);
                if (messages == null || messages.isEmpty()) {
                    logger.info("Skipping {} as it is up-to-date.", task);
                    return true;
                }
                if (logger.isInfoEnabled()) {
                    Formatter formatter = new Formatter();
                    formatter.format("Executing %s due to:", task);
                    for (String message : messages) {
                        formatter.format("%n%s", message);
                    }
                    logger.info(formatter.toString());
                }
                return false;
            }

            public void invalidate() {
                for (File file : thisExecution.outputFiles.keySet()) {
                    OutputGenerators generators = outputs.get(file);
                    generators.remove(key);
                }
            }

            public void update() {
                thisExecution.snapshotOutputFiles();
                for (Map.Entry<File, OutputFileInfo> entry : thisExecution.outputFiles.entrySet()) {
                    OutputGenerators generators = outputs.get(entry.getKey());
                    if (entry.getValue().isFile) {
                        generators.replace(key, thisExecution);
                    } else {
                        generators.add(key, thisExecution);
                    }
                }
                save();
            }
        };
    }

    private TaskInfo getThisExecution(TaskInternal task) {
        return new TaskInfo(task);
    }

    private TaskExecution getLastExecution(TaskKey key, TaskInfo thisExecution) {
        TaskExecution taskInfo = new EmptyTaskInfo();
        List<String> outOfDateMessages = new ArrayList<String>();
        for (File outputFile : thisExecution.outputFiles.keySet()) {
            if (!outputFile.exists()) {
                // Discard previous state for this output file
                outputs.put(outputFile, new OutputGenerators());
                outOfDateMessages.add(String.format("%s does not exist.", outputFile));
                continue;
            }
            OutputGenerators generators = outputs.get(outputFile);
            if (generators == null) {
                outputs.put(outputFile, new OutputGenerators());
                outOfDateMessages.add(String.format("No history is available for %s.", outputFile));
                continue;
            }
            TaskInfo lastExecution = generators.get(key);
            if (lastExecution == null) {
                outOfDateMessages.add(String.format("Task did not produce %s.", outputFile));
                continue;
            }
//            if (taskInfo != null && taskInfo != lastExecution) {
//                outOfDateMessages.add(String.format("Some other task produced %s.", outputFile));
//                continue;
//            }
            taskInfo = lastExecution;
        }
        return outOfDateMessages.isEmpty() ? taskInfo : new EmptyTaskInfo(outOfDateMessages);
    }

    private void loadTasks(TaskInternal task) {
        cache = repository.getCacheFor(task.getProject().getGradle(), "taskArtifacts", Collections.EMPTY_MAP);
        if (cache.isValid()) {
            load();
        } else {
            outputs = new HashMap<File, OutputGenerators>();
        }
    }

    private void save() {
        try {
            ObjectOutputStream outStr = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(
                    getStateFile())));
            try {
                outStr.writeObject(outputs);
            } finally {
                outStr.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        cache.update();
    }

    private void load() {
        try {
            ObjectInputStream inStr = new ObjectInputStream(new BufferedInputStream(new FileInputStream(
                    getStateFile())));
            try {
                outputs = (Map<File, OutputGenerators>) inStr.readObject();
            } finally {
                inStr.close();
            }
        } catch (Exception e) {
            throw new UncheckedIOException(e);
        }
    }

    private File getStateFile() {
        return new File(cache.getBaseDir(), "tasks.bin");
    }

    private static class OutputGenerators implements Serializable {
        private final Map<TaskKey, TaskInfo> generators = new HashMap<TaskKey, TaskInfo>();

        public TaskInfo remove(TaskKey task) {
            return generators.remove(task);
        }

        public void add(TaskKey key, TaskInfo info) {
            generators.put(key, info);
        }

        public void replace(TaskKey key, TaskInfo info) {
            generators.clear();
            generators.put(key, info);
        }

        public TaskInfo get(TaskKey key) {
            return generators.get(key);
        }
    }

    private static class TaskKey implements Serializable {
        private final String type;
        private final String path;

        private TaskKey(TaskInternal task) {
            path = task.getPath();
            type = task.getClass().getName();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            TaskKey other = (TaskKey) o;
            return other.type.equals(type) && other.path.equals(path);
        }

        @Override
        public int hashCode() {
            return type.hashCode() ^ path.hashCode();
        }
    }

    private interface TaskExecution {
    }

    private static class EmptyTaskInfo implements TaskExecution {
        private final List<String> outOfDateMessages;

        public EmptyTaskInfo() {
            outOfDateMessages = Arrays.asList("Task does not produce any output files");
        }

        public EmptyTaskInfo(List<String> outOfDateMessages) {
            this.outOfDateMessages = outOfDateMessages;
        }
    }

    private static class TaskInfo implements Serializable, TaskExecution {
        private final Map<File, InputFileInfo> inputFiles = new HashMap<File, InputFileInfo>();
        private final Map<File, OutputFileInfo> outputFiles = new HashMap<File, OutputFileInfo>();

        public TaskInfo(TaskInternal task) {
            for (File file : task.getInputs().getInputFiles()) {
                inputFiles.put(file, new InputFileInfo(file));
            }
            for (File file : task.getOutputs().getOutputFiles()) {
                outputFiles.put(file, null);
            }
        }

        public void snapshotOutputFiles() {
            for (File file : outputFiles.keySet()) {
                outputFiles.put(file, new OutputFileInfo(file));
            }
        }

        public List<String> isSameAs(TaskExecution last) {
            if (last instanceof EmptyTaskInfo) {
                EmptyTaskInfo emptyTaskInfo = (EmptyTaskInfo) last;
                return emptyTaskInfo.outOfDateMessages;
            }
            
            if (inputFiles.isEmpty()) {
                return Arrays.asList("Task does not accept any input files.");
            }

            TaskInfo lastExecution = (TaskInfo) last;

            if (!outputFiles.keySet().equals(lastExecution.outputFiles.keySet())) {
                return Arrays.asList("The set of output files has changed.");
            }

            for (Map.Entry<File, OutputFileInfo> entry : lastExecution.outputFiles.entrySet()) {
                File file = entry.getKey();
                OutputFileInfo lastOutputFile = entry.getValue();
                if (!lastOutputFile.isUpToDate(file)) {
                    return Arrays.asList(String.format("Output file %s has changed.", file));
                }
            }

            if (!inputFiles.keySet().equals(lastExecution.inputFiles.keySet())) {
                return Arrays.asList("The set of input files has changed");
            }

            for (Map.Entry<File, InputFileInfo> entry : inputFiles.entrySet()) {
                File file = entry.getKey();
                InputFileInfo inputFile = entry.getValue();
                InputFileInfo lastInputFile = lastExecution.inputFiles.get(file);
                if (!inputFile.isUpToDate(lastInputFile)) {
                    return Arrays.asList(String.format("Input file %s has changed.", file));
                }
            }

            return null;
        }
    }

    private static class OutputFileInfo implements Serializable {
        private final boolean isFile;

        private OutputFileInfo(File file) {
            isFile = file.isFile();
        }

        public boolean isUpToDate(File file) {
            return file.exists() && file.isFile() == isFile;
        }
    }

    private static class InputFileInfo implements Serializable {
        private static final byte FILE = 0;
        private static final byte DIR = 1;
        private static final byte MISSING = 2;
        private final short type;
        private final String hash;

        private InputFileInfo(File file) {
            if (file.isFile()) {
                type = FILE;
                hash = HashUtil.createHash(file);
            } else if (file.isDirectory()) {
                type = DIR;
                hash = null;
            } else {
                type = MISSING;
                hash = null;
            }
        }

        public boolean isUpToDate(InputFileInfo lastInputFile) {
            if (type != lastInputFile.type) {
                return false;
            }
            if (type == FILE && !hash.equals(lastInputFile.hash)) {
                return false;
            }
            return true;
        }
    }
}