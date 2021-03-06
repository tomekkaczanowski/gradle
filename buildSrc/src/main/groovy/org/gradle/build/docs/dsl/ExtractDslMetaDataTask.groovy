package org.gradle.build.docs.dsl

import org.codehaus.groovy.groovydoc.GroovyFieldDoc
import org.codehaus.groovy.groovydoc.GroovyMethodDoc
import org.codehaus.groovy.groovydoc.GroovyRootDoc
import org.codehaus.groovy.tools.groovydoc.GroovyDocTool
import org.codehaus.groovy.tools.groovydoc.SimpleGroovyClassDoc
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.TaskAction

/**
 * Extracts meta-data from the Groovy and Java source files which make up the Gradle DSL. Persists the meta-data to a file
 * for later use in generating the docbook source for the DSL.
 */
class ExtractDslMetaDataTask extends SourceTask {
    @OutputFile
    def File destFile

    @TaskAction
    def extract() {
        project.delete(temporaryDir)
        project.copy { from source; into temporaryDir }
        GroovyDocTool groovyDoc = new GroovyDocTool([temporaryDir.absolutePath] as String[])
        List<String> files = []
        project.fileTree(temporaryDir).visit { FileVisitDetails fvd ->
            if (!fvd.isDirectory()) {
                files << fvd.path
            }
        }
        groovyDoc.add(files)

        Map<String, ClassMetaData> allClasses = [:]
        GroovyRootDoc rootDoc = groovyDoc.rootDoc
        rootDoc.classes().each { SimpleGroovyClassDoc doc ->
            String className = doc.qualifiedTypeName()
            String superClassName = doc.superclass()?.qualifiedTypeName()
            ClassMetaData classMetaData = new ClassMetaData(superClassName, doc.isGroovy())
            allClasses[className] = classMetaData

            doc.methods().each { GroovyMethodDoc method ->
                if (method.name().matches("get.+")) {
                    String propName = method.name()[3].toLowerCase() + method.name().substring(4)
                    classMetaData.addReadableProperty(propName, method.returnType().qualifiedTypeName())
                } else if (method.name().matches("set.+")) {
                    String propName = method.name()[3].toLowerCase() + method.name().substring(4)
                    classMetaData.addWriteableProperty(propName, method.returnType().qualifiedTypeName())
                }
            }

            // This bit of ugliness is to get Groovydoc to resolve the type names for properties by pretending that
            // they are fields
            doc.fields.clear()
            doc.fields.addAll(doc.properties())
            doc.methods.clear()
            doc.resolve(rootDoc)

            doc.properties().each { GroovyFieldDoc field ->
                classMetaData.addReadableProperty(field.name(), field.type().qualifiedTypeName())
                classMetaData.addWriteableProperty(field.name(), field.type().qualifiedTypeName())
            }
        }

        destFile.withObjectOutputStream { ObjectOutputStream outstr ->
            outstr.writeObject(allClasses)
        }
    }
}
