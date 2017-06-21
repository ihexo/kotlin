/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.jps.build

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.containers.HashMap
import com.intellij.util.containers.StringInterner
import org.jetbrains.kotlin.TestWithWorkingDir
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.OutputMessageUtil
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compilerRunner.*
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.components.LookupInfo
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.incremental.isKotlinFile
import org.jetbrains.kotlin.incremental.makeModuleFile
import org.jetbrains.kotlin.incremental.testingUtils.TouchPolicy
import org.jetbrains.kotlin.incremental.testingUtils.copyTestSources
import org.jetbrains.kotlin.incremental.testingUtils.getModificationsToPerform
import org.jetbrains.kotlin.incremental.utils.TestMessageCollector
import org.jetbrains.kotlin.preloading.ClassCondition
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.utils.PathUtil
import java.io.*
import java.util.*

private val DECLARATION_KEYWORDS = listOf("interface", "class", "enum class", "object", "fun", "operator fun", "val", "var")
private val DECLARATION_STARTS_WITH = DECLARATION_KEYWORDS.map { it + " " }

/**
 * Tests lookup tracking. The test supports limited incremental compilation:
 * removing or modifying package is not supported (that would require using full incremental cache).
 */
abstract class AbstractLookupTrackerTest : TestWithWorkingDir() {
    // ignore KDoc like comments which starts with `/**`, example: /** text */
    private val COMMENT_WITH_LOOKUP_INFO = "/\\*[^*]+\\*/".toRegex()

    private fun removeCommentsCommentsWithLookupInfo(files: Iterable<File>) {
        files.forEach {
            val content = it.readText()
            it.writeText(content.replace(COMMENT_WITH_LOOKUP_INFO, ""))
        }
    }

    protected lateinit var srcDir: File
    protected lateinit var outDir: File
    private var isICEnabledBackup: Boolean = false

    override fun setUp() {
        super.setUp()
        srcDir = File(workingDir, "src").apply { mkdirs() }
        outDir = File(workingDir, "out")
        isICEnabledBackup = IncrementalCompilation.isEnabled()
        IncrementalCompilation.setIsEnabled(true)
    }

    override fun tearDown() {
        IncrementalCompilation.setIsEnabled(isICEnabledBackup)
        super.tearDown()
    }

    fun doTest(path: String) {
        val sb = StringBuilder()
        fun StringBuilder.indentln(string: String) {
            appendln("  $string")
        }
        fun CompilerOutput.logOutput(stepName: String) {
            sb.appendln("==== $stepName ====")

            sb.appendln("Compiled and checked lookups in files:")
            for (compiledFile in compiledFiles.sortedBy { it.canonicalPath }) {
                val lookupCount = lookupsCount[compiledFile]
                val lookupStatus = when {
                    lookupCount == 0 -> "(no lookups)"
                    lookupCount != null && lookupCount > 0 -> ""
                    else -> "(unknown)"
                }
                sb.indentln("${compiledFile.toRelativeString(workingDir)}$lookupStatus")
            }

            sb.appendln("Exit code: $exitCode")
            errors.forEach(sb::indentln)

            sb.appendln()
        }

        val testDir = File(path)
        val workToOriginalFileMap = HashMap(copyTestSources(testDir, srcDir, filePrefix = ""))
        var dirtyFiles = srcDir.walk().filterTo(HashSet()) { it.isKotlinFile() }
        val incrementalData = IncrementalData()
        val steps = getModificationsToPerform(testDir, moduleNames = null, allowNoFilesWithSuffixInTestData = true, touchPolicy = TouchPolicy.CHECKSUM)
                .filter { it.isNotEmpty() }

        makeAndCheckLookups(dirtyFiles, workToOriginalFileMap, incrementalData).logOutput("INITIAL BUILD")
        for ((i, modifications) in steps.withIndex()) {
            dirtyFiles = modifications.mapNotNullTo(HashSet()) { it.perform(workingDir, workToOriginalFileMap) }
            makeAndCheckLookups(dirtyFiles, workToOriginalFileMap, incrementalData).logOutput("STEP ${i + 1}")
        }

        val expectedBuildLog = File(testDir, "build.log")
        UsefulTestCase.assertSameLinesWithFile(expectedBuildLog.canonicalPath, sb.toString())
    }

    private class CompilerOutput(
        val exitCode: String,
        val errors: List<String>,
        val compiledFiles: Iterable<File>,
        val lookupsCount: Map<File, Int>
    )
    private class IncrementalData(val sourceToOutput: MutableMap<File, MutableSet<File>> = hashMapOf())

    private fun makeAndCheckLookups(
            filesToCompile: Iterable<File>,
            workingToOriginalFileMap: Map<File, File>,
            incrementalData: IncrementalData
    ): CompilerOutput {
        removeCommentsCommentsWithLookupInfo(filesToCompile)

        for (dirtyFile in filesToCompile) {
            incrementalData.sourceToOutput.remove(dirtyFile)?.forEach {
                it.delete()
            }
        }

        val lookupTracker = TestLookupTracker()
        val messageCollector = TestMessageCollector()
        val outputItemsCollector = OutputItemsCollectorImpl()
        val environment = createEnvironment(lookupTracker, messageCollector, outputItemsCollector)
        val exitCode = runCompiler(filesToCompile, environment)

        for (output in outputItemsCollector.outputs) {
            val outputFile = output.outputFile
            if (outputFile.extension == "kotlin_module") continue

            for (sourceFile in output.sourceFiles) {
                val outputsForSource = incrementalData.sourceToOutput.getOrPut(sourceFile) { hashSetOf() }
                outputsForSource.add(outputFile)
            }
        }

        val lookupsCount = checkLookups(filesToCompile, lookupTracker, workingToOriginalFileMap)
        return CompilerOutput(exitCode.toString(), messageCollector.errors, filesToCompile, lookupsCount)
    }

    private fun createEnvironment(
            lookupTracker: LookupTracker,
            messageCollector: MessageCollector,
            outputItemsCollector: OutputItemsCollectorImpl
    ): JpsCompilerEnvironment {
        val paths = PathUtil.getKotlinPathsForDistDirectory()
        val services = Services.Builder().run {
            register(LookupTracker::class.java, lookupTracker)
            build()
        }
        val classesToLoadByParent = ClassCondition { className ->
            className.startsWith("org.jetbrains.kotlin.load.kotlin.incremental.components.")
            || className.startsWith("org.jetbrains.kotlin.incremental.components.")
            || className == "org.jetbrains.kotlin.config.Services"
            || className.startsWith("org.apache.log4j.") // For logging from compiler
            || className == "org.jetbrains.kotlin.progress.CompilationCanceledStatus"
            || className == "org.jetbrains.kotlin.progress.CompilationCanceledException"
            || className == "org.jetbrains.kotlin.modules.TargetId"
        }
        val wrappedMessageCollector = MessageCollectorWrapper(messageCollector, outputItemsCollector)
        return JpsCompilerEnvironment(paths, services, classesToLoadByParent, wrappedMessageCollector, outputItemsCollector)
    }

    private fun runCompiler(filesToCompile: Iterable<File>, env: JpsCompilerEnvironment): Any? {
        val module = makeModuleFile(name = "test",
                                    isTest = true,
                                    outputDir = outDir,
                                    sourcesToCompile = filesToCompile.toList(),
                                    javaSourceRoots = listOf(srcDir),
                                    classpath = listOf(outDir).filter { it.exists() },
                                    friendDirs = emptyList())
        outDir.mkdirs()
        val args = arrayOf("-module", module.canonicalPath, "-Xreport-output-files")

        try {
            val stream = ByteArrayOutputStream()
            val out = PrintStream(stream)
            val exitCode = CompilerRunnerUtil.invokeExecMethod(K2JVMCompiler::class.java.name, args, env, out)
            val reader = BufferedReader(StringReader(stream.toString()))
            CompilerOutputParser.parseCompilerMessagesFromReader(env.messageCollector, reader, env.outputItemsCollector)

            return exitCode
        }
        finally {
            module.delete()
        }
    }

    private fun checkLookups(
            compiledFiles: Iterable<File>,
            lookupTracker: TestLookupTracker,
            workingToOriginalFileMap: Map<File, File>
    ): Map<File, Int> {
        fun checkLookupsInFile(expectedFile: File, actualFile: File, lookupsFromFile: List<LookupInfo>) {
            val text = actualFile.readText()

            val matchResult = COMMENT_WITH_LOOKUP_INFO.find(text)
            if (matchResult != null) {
                throw AssertionError("File $actualFile contains multiline comment in range ${matchResult.range}")
            }

            val lines = text.lines().toMutableList()

            for ((line, lookupsFromLine) in lookupsFromFile.groupBy { it.position.line }) {
                val columnToLookups = lookupsFromLine.groupBy { it.position.column }.toList().sortedBy { it.first }

                val lineContent = lines[line - 1]
                val parts = ArrayList<CharSequence>(columnToLookups.size * 2)

                var start = 0

                for ((column, lookupsFromColumn) in columnToLookups) {
                    val end = column - 1
                    parts.add(lineContent.subSequence(start, end))

                    val lookups = lookupsFromColumn.distinct().joinToString(separator = " ", prefix = "/*", postfix = "*/") {
                        val rest = lineContent.substring(end)

                        val name =
                                when {
                                    rest.startsWith(it.name) || // same name
                                    rest.startsWith("$" + it.name) || // backing field
                                    DECLARATION_STARTS_WITH.any { rest.startsWith(it) } // it's declaration
                                         -> ""
                                    else -> "(" + it.name + ")"
                                }

                        it.scopeKind.toString()[0].toLowerCase().toString() + ":" + it.scopeFqName.let { if (it.isNotEmpty()) it else "<root>" } + name
                    }

                    parts.add(lookups)

                    start = end
                }

                lines[line - 1] = parts.joinToString("") + lineContent.subSequence(start, lineContent.length)
            }

            val actual = lines.joinToString("\n")

            KotlinTestUtils.assertEqualsToFile(expectedFile, actual)
        }

        val fileToLookups = lookupTracker.lookups.groupBy { File(it.filePath) }
        return compiledFiles.associate { actualFile ->
            val expectedFile = workingToOriginalFileMap[actualFile]!!
            val lookupsInFile = fileToLookups[actualFile]
            lookupsInFile?.let { checkLookupsInFile(expectedFile, actualFile, it) }
            actualFile to (lookupsInFile?.size ?: 0)
        }
    }
}

class MessageCollectorWrapper(
        private val delegate: MessageCollector,
        private val outputCollector: OutputItemsCollector
) : MessageCollector by delegate {
    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
        // TODO: consider adding some other way of passing input -> output mapping from compiler, e.g. dedicated service
        OutputMessageUtil.parseOutputMessage(message)?.let {
            outputCollector.add(it.sourceFiles, it.outputFile)
        }
        delegate.report(severity, message, location)
    }
}

class TestLookupTracker : LookupTracker {
    val lookups = arrayListOf<LookupInfo>()
    private val interner = StringInterner()

    override val requiresPosition: Boolean
        get() = true

    override fun record(filePath: String, position: Position, scopeFqName: String, scopeKind: ScopeKind, name: String) {
        val internedFilePath = interner.intern(filePath)
        val internedScopeFqName = interner.intern(scopeFqName)
        val internedName = interner.intern(name)

        lookups.add(LookupInfo(internedFilePath, position, internedScopeFqName, scopeKind, internedName))
    }
}


