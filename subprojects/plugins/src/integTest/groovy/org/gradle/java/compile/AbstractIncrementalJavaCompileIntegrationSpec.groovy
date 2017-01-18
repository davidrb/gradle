/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.java.compile

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.language.fixtures.AnnotationProcessorFixture

abstract class AbstractIncrementalJavaCompileIntegrationSpec extends AbstractIntegrationSpec {
    def setup() {
        settingsFile << """
include 'a', 'b'
"""
        buildFile << """
            allprojects {
                apply plugin: 'java'
            }
        """
    }

    def useIncrementalCompile() {
        buildFile << """
            allprojects {
                tasks.withType(JavaCompile) {
                    options.incremental = true
                }
            }
        """
    }

    def useJar() {
        buildFile << """
            allprojects {
                tasks.withType(JavaCompile) {
                    // Use forking to work around javac's jar cache
                    options.fork = true
                }
            }
"""
    }

    def useClassesDir() {
        buildFile << """import static org.gradle.api.attributes.Usage.*;
            allprojects {
                dependencies {
                    attributesSchema {
                        attribute(Attribute.of('test.usage', String))
                    }
                }
                configurations.compile {
                    attribute USAGE_ATTRIBUTE, FOR_COMPILE
                    attribute 'test.usage', 'classdir'
                }
                configurations.compileClasspath {
                    attribute USAGE_ATTRIBUTE, FOR_COMPILE
                    attribute 'test.usage', 'classdir'
                    canBeConsumed = false
                }
                artifacts {
                    compile file: compileJava.destinationDir, builtBy: compileJava
                    compile file: processResources.destinationDir, builtBy: processResources
                }
            }
        """
    }

    def "doesn't recompile when implementation class changes in ABI compatible way"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """
            public class ToolImpl { public void execute() { int i = 12; } }
        """
        file("b/src/main/java/Main.java") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        sourceFile.text = """
            public class ToolImpl { public void execute() { String s = toString(); } }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        sourceFile.text = """
            public class ToolImpl { public static ToolImpl instance; public void execute() { String s = toString(); } }
"""

        then:
        succeeds ':b:compileJava'
        executedAndNotSkipped ':a:compileJava', ":b:compileJava"
    }

    def "doesn't recompile when implementation resource is changed in various ways"() {
        given:
        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
        """
        def sourceFile = file("a/src/main/java/ToolImpl.java")
        sourceFile << """
            public class ToolImpl { public void execute() { int i = 12; } }
        """
        def resourceFile = file("a/src/main/resources/a.properties")
        resourceFile.text = "a = 12"
        file("b/src/main/java/Main.java") << """
            public class Main { ToolImpl t = new ToolImpl(); }
        """

        when:
        succeeds ':b:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'

        when:
        resourceFile.text = "a = 11"

        then:
        succeeds ':b:compileJava'
        skipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        resourceFile.delete()

        then:
        succeeds ':b:compileJava'
        skipped ':a:compileJava'
        skipped ':b:compileJava'

        when:
        file("a/src/main/resources/org/gradle/b.properties").createFile()

        then:
        succeeds ':b:compileJava'
        skipped ':a:compileJava'
        skipped ':b:compileJava'
    }

    def "recompiles source when annotation processor implementation changes when compile classpath is used for annotation processor discovery"() {
        settingsFile << "include 'c'"

        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
            project(':c') {
                dependencies {
                    compile project(':b')
                }
                task run(type: JavaExec) {
                    main = 'TestApp'
                    classpath = sourceSets.main.runtimeClasspath
                }
            }
        """

        def fixture = new AnnotationProcessorFixture()

        // A library class used by processor at runtime, but not the generated classes
        fixture.writeSupportLibraryTo(file("a"))

        // The processor and annotation
        fixture.writeApiTo(file("b"))
        fixture.writeAnnotationProcessorTo(file("b"))

        // The class that is the target of the processor
        file('c/src/main/java/TestApp.java') << '''
            @Helper
            class TestApp { 
                public static void main(String[] args) {
                    System.out.println(new TestAppHelper().getValue()); // generated class
                }
            }
'''

        when:
        run(':c:run')

        then:
        executedAndNotSkipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('greetings')

        when:
        run(':c:run')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')
        outputContains('greetings')

        when:
        // Update the library class
        fixture.message = 'hello'
        fixture.writeSupportLibraryTo(file("a"))

        run(':c:run')

        then:
        executedAndNotSkipped(':a:compileJava')
        skipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('hello')

        when:
        run(':c:run')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')
        outputContains('hello')

        when:
        // Update the processor class
        fixture.suffix = 'world'
        fixture.writeAnnotationProcessorTo(file("b"))

        run(':c:run')

        then:
        skipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('hello world')
    }

    def "recompiles source when annotation processor implementation changes when separate annotation processor classpath is used for annotation processor discovery"() {
        settingsFile << "include 'c'"

        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
            project(':c') {
                configurations {
                    processor
                }
                dependencies {
                    compile project(':a')
                    processor project(':b')
                }
                compileJava.options.annotationProcessorPath = configurations.processor
                compileJava.options.fork = true
                task run(type: JavaExec) {
                    main = 'TestApp'
                    classpath = sourceSets.main.runtimeClasspath
                }
            }
        """

        def fixture = new AnnotationProcessorFixture()

        // The annotation
        fixture.writeApiTo(file("a"))

        // The processor and library
        fixture.writeSupportLibraryTo(file("b"))
        fixture.writeAnnotationProcessorTo(file("b"))

        // The class that is the target of the processor
        file('c/src/main/java/TestApp.java') << '''
            @Helper
            class TestApp { 
                public static void main(String[] args) {
                    System.out.println(new TestAppHelper().getValue()); // generated class
                }
            }
'''

        when:
        run(':c:run')

        then:
        executedAndNotSkipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('greetings')

        when:
        run(':c:run')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')
        outputContains('greetings')

        when:
        // Update the library class
        fixture.message = 'hello'
        fixture.writeSupportLibraryTo(file("b"))

        run(':c:run')

        then:
        skipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('hello')

        when:
        run(':c:run')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')
        outputContains('hello')

        when:
        // Update the processor class
        fixture.suffix = 'world'
        fixture.writeAnnotationProcessorTo(file("b"))

        run(':c:run')

        then:
        skipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')
        outputContains('hello world')
    }

    def "ignores annotation processor implementation when included in the compile classpath but annotation processing is disabled"() {
        settingsFile << "include 'c'"

        buildFile << """
            project(':b') {
                dependencies {
                    compile project(':a')
                }
            }
            project(':c') {
                dependencies {
                    compile project(':b')
                }
                compileJava.options.annotationProcessorPath = files()
            }
        """

        def fixture = new AnnotationProcessorFixture()

        fixture.writeSupportLibraryTo(file("a"))
        fixture.writeApiTo(file("b"))
        fixture.writeAnnotationProcessorTo(file("b"))

        file('c/src/main/java/TestApp.java') << '''
            @Helper
            class TestApp { 
                public static void main(String[] args) {
                }
            }
'''

        when:
        run(':c:compileJava')

        then:
        executedAndNotSkipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        executedAndNotSkipped(':c:compileJava')

        when:
        run(':c:compileJava')

        then:
        skipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')

        when:
        // Update the library class
        fixture.message = 'hello'
        fixture.writeSupportLibraryTo(file("a"))

        run(':c:compileJava')

        then:
        executedAndNotSkipped(':a:compileJava')
        skipped(':b:compileJava')
        skipped(':c:compileJava')

        when:
        // Update the processor class
        fixture.suffix = 'world'
        fixture.writeAnnotationProcessorTo(file("b"))

        run(':c:compileJava')

        then:
        skipped(':a:compileJava')
        executedAndNotSkipped(':b:compileJava')
        skipped(':c:compileJava')
    }

    def "change to transitive super-class in different project should trigger recompilation"() {
        given:
        settingsFile << "include 'c'"

        buildFile << """
            project(':a') {
                dependencies {
                    compile project(':b')
                }
            }
            project(':b') {
                dependencies {
                    compile project(':c')
                }
            }
        """

        file("a/src/main/java/A.java") << "public class A extends B { void a() { b(); String c = c(); } }"
        file("b/src/main/java/B.java") << "public class B extends C { void b() { d(); } }"
        file("c/src/main/java/C.java") << "public class C { String c() { return null; }; void d() {} }"

        when:
        succeeds ':a:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'
        executedAndNotSkipped ':c:compileJava'

        when:
        file("c/src/main/java/C.java").text = "public class C { void c() {}; void d() {} }"

        then:
        fails ':a:compileJava'
        errorOutput.contains 'String c = c()'

        and:
        executedAndNotSkipped ':b:compileJava'
        executedAndNotSkipped ':c:compileJava'

    }

    def "change to transitive super-class in different project should trigger recompilation 2"() {
        given:
        settingsFile << "include 'c', 'd'"

        buildFile << """
            project(':a') {
                dependencies {
                    compile project(':b')
                }
            }
            project(':b') {
                dependencies {
                    compile project(':c')
                }
            }
            project(':c') {
                dependencies {
                    compile project(':d')
                }
            }
        """

        file("a/src/main/java/A.java") << "public class A extends B { void a() { b(); String d = d(); } }"
        file("b/src/main/java/B.java") << "public class B extends C { void b() { } }"
        file("c/src/main/java/C.java") << "public class C extends D { void c() { }; }"
        file("d/src/main/java/D.java") << "public class D { String d() { return null; } }"

        when:
        succeeds ':a:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava'
        executedAndNotSkipped ':b:compileJava'
        executedAndNotSkipped ':c:compileJava'
        executedAndNotSkipped ':d:compileJava'

        when:
        file("d/src/main/java/D.java").text = "public class D { void d() { } }"

        then:
        fails ':a:compileJava'
        errorOutput.contains 'String d = d();'

        and:
        executedAndNotSkipped ':b:compileJava'
        executedAndNotSkipped ':c:compileJava'
        executedAndNotSkipped ':d:compileJava'

    }
}
