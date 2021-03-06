/*
 * Copyright 2018 the original author or authors.
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
package org.gradle.internal.fingerprint.impl

import org.gradle.api.file.FileCollection
import org.gradle.api.internal.cache.StringInterner
import org.gradle.api.internal.file.TestFiles
import org.gradle.api.internal.file.collections.ImmutableFileCollection
import org.gradle.internal.change.ChangeType
import org.gradle.internal.change.CollectingChangeVisitor
import org.gradle.internal.change.FileChange
import org.gradle.internal.fingerprint.FileCollectionFingerprint
import org.gradle.internal.hash.TestFileHasher
import org.gradle.internal.snapshot.WellKnownFileLocations
import org.gradle.internal.snapshot.impl.DefaultFileSystemMirror
import org.gradle.internal.snapshot.impl.DefaultFileSystemSnapshotter
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.util.ChangeListener
import org.junit.Rule
import spock.lang.Specification

class AbsolutePathFileCollectionFingerprinterTest extends Specification {
    def stringInterner = new StringInterner()
    def fileSystemMirror = new DefaultFileSystemMirror(Stub(WellKnownFileLocations))
    def fingerprinter = new AbsolutePathFileCollectionFingerprinter(stringInterner, new DefaultFileSystemSnapshotter(new TestFileHasher(), stringInterner, TestFiles.fileSystem(), fileSystemMirror))
    def listener = Mock(ChangeListener)

    @Rule
    public final TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()

    def "retains order of files in the snapshot"() {
        given:
        TestFile file = tmpDir.createFile('file1')
        TestFile file2 = tmpDir.createFile('file2')
        TestFile file3 = tmpDir.createFile('file3')

        when:
        def fingerprint = fingerprinter.fingerprint(files(file, file2, file3))

        then:
        fingerprint.fingerprints.keySet().collect { new File(it) } == [file, file2, file3]
    }

    def getElementsIncludesRootDirectories() {
        given:
        TestFile file = tmpDir.createFile('file1')
        TestFile dir = tmpDir.createDir('dir')
        TestFile dir2 = dir.createDir('dir2')
        TestFile file2 = dir2.createFile('file2')
        TestFile noExist = tmpDir.file('file3')

        when:
        def fingerprint = fingerprinter.fingerprint(files(file, dir, noExist))

        then:
        fingerprint.fingerprints.keySet().collect { new File(it) } == [file, dir, dir2, file2, noExist]
    }

    def "retains order of elements in the snapshot"() {
        given:
        TestFile file = tmpDir.createFile('file1')
        TestFile file2 = tmpDir.file('file2')
        TestFile file3 = tmpDir.file('file3')
        TestFile file4 = tmpDir.createFile('file4')

        when:
        def fingerprint = fingerprinter.fingerprint(files(file, file2, file3, file4))

        then:
        fingerprint.fingerprints.keySet().collect { new File(it) } == [file, file2, file3, file4]
    }

    def generatesEventWhenFileAdded() {
        given:
        TestFile file1 = tmpDir.createFile('file1')
        TestFile file2 = tmpDir.createFile('file2')

        when:
        def fingerprint = fingerprinter.fingerprint(files(file1))
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(files(file1, file2)), fingerprint, listener)

        then:
        1 * listener.added(file2.path)
        0 * _
    }

    def doesNotGenerateEventWhenFileAddedAndAddEventsAreFiltered() {
        given:
        TestFile file1 = tmpDir.createFile('file1')
        TestFile file2 = tmpDir.file('file2')
        TestFile file3 = tmpDir.createFile('file3')
        TestFile file4 = tmpDir.createDir('file4')

        when:
        def fingerprint = fingerprinter.fingerprint(files(file1, file2))
        file2.createFile()
        def target = fingerprinter.fingerprint(files(file1, file2, file3, file4))
        def visitor = new CollectingChangeVisitor()
        target.visitChangesSince(fingerprint, "TYPE", false, visitor)
        visitor.changes.empty

        then:
        0 * _
    }

    def generatesEventWhenFileRemoved() {
        given:
        TestFile file1 = tmpDir.createFile('file1')
        TestFile file2 = tmpDir.createFile('file2')

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(files(file1, file2))
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(files(file1)), fingerprint, listener)

        then:
        1 * listener.removed(file2.path)
        0 * _
    }

    def doesNotGenerateEventForFileWhoseTypeAndMetaDataAndContentHaveNotChanged() {
        given:
        TestFile file = tmpDir.createFile('file')
        file.setLastModified(1234L)

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(files(file))
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(files(file)), fingerprint, listener)
        file.setLastModified(45600L)
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(files(file)), fingerprint, listener)

        then:
        0 * listener._
    }

    def generatesEventWhenFileBecomesADirectory() {
        given:
        TestFile root = tmpDir.createDir('root')
        TestFile file = root.createFile('file')
        def fileCollection = files(root)

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(fileCollection)
        file.delete()
        file.createDir()
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(fileCollection), fingerprint, listener)

        then:
        1 * listener.changed(file.path)
        0 * _
    }

    def generatesEventWhenFileContentChanges() {
        TestFile file = tmpDir.createFile('file')

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(files(file))
        file.write('new content')
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(files(file)), fingerprint, listener)

        then:
        1 * listener.changed(file.path)
        0 * _
    }

    def doesNotGenerateEventForDirectoryThatHasNotChanged() {
        TestFile dir = tmpDir.createDir('dir')

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(files(dir))
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(files(dir)), fingerprint, listener)

        then:
        0 * _
    }

    def generatesEventForDirectoryThatBecomesAFile() {
        TestFile root = tmpDir.createDir('root')
        def fileCollection = files(root)
        TestFile dir = root.createDir('dir')

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(fileCollection)
        dir.deleteDir()
        dir.createFile()
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(fileCollection), fingerprint, listener)

        then:
        1 * listener.changed(dir.path)
        0 * listener._
    }

    def doesNotGenerateEventForMissingFileThatStillIsMissing() {
        TestFile file = tmpDir.file('unknown')

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(files(file))
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(files(file)), fingerprint, listener)

        then:
        0 * _
    }

    def generatesEventWhenMissingFileIsCreated() {
        TestFile root = tmpDir.createDir('root')
        def fileCollection = files(root)
        TestFile file = root.file('newfile')

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(fileCollection)
        file.createFile()
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(fileCollection), fingerprint, listener)

        then:
        1 * listener.added(file.path)
    }

    def generatesEventWhenFileIsDeleted() {
        TestFile root = tmpDir.createDir('root')
        def fileCollection = files(root)
        TestFile file = root.createFile('file')

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(fileCollection)
        file.delete()
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(fileCollection), fingerprint, listener)

        then:
        1 * listener.removed(file.path)
    }

    def ignoresDuplicatesInFileCollection() {
        TestFile file1 = tmpDir.createFile('file')
        TestFile file2 = tmpDir.createFile('file')

        when:
        FileCollectionFingerprint fingerprint = fingerprinter.fingerprint(files(file1, file2))
        fileSystemMirror.beforeOutputChange()
        changes(fingerprinter.fingerprint(files(file1)), fingerprint, listener)

        then:
        0 * _
    }

    def canCreateEmptySnapshot() {
        TestFile file = tmpDir.createFile('file')

        when:
        FileCollectionFingerprint fingerprint = FileCollectionFingerprint.EMPTY
        FileCollectionFingerprint newFingerprint = fingerprinter.fingerprint(files(file))
        fileSystemMirror.beforeOutputChange()
        changes(newFingerprint, fingerprint, listener)

        then:
        fingerprint.fingerprints.isEmpty()
        1 * listener.added(file.path)
        0 * listener._
    }

    private static void changes(FileCollectionFingerprint newFingerprint, FileCollectionFingerprint oldFingerprint, ChangeListener<String> listener) {
        newFingerprint.visitChangesSince(oldFingerprint, "TYPE", true) { FileChange change ->
            switch (change.type) {
                case ChangeType.ADDED:
                    listener.added(change.path)
                    break
                case ChangeType.MODIFIED:
                    listener.changed(change.path)
                    break
                case ChangeType.REMOVED:
                    listener.removed(change.path)
                    break
            }
            return true
        }
    }

    private static FileCollection files(File... files) {
        ImmutableFileCollection.of(files)
    }
}
