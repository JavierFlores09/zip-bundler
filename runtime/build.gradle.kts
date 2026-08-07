plugins {
    id("zip-bundler.java-library-conventions")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}
