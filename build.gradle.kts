plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("com.google.devtools.ksp") version "2.3.12"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.meetme"
version = "0.0.1-SNAPSHOT"
description = "AI-assisted meeting coordination backend"

extra["kotlin-coroutines.version"] = "1.11.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    val komapperVersion = "7.0.0"
    enforcedPlatform("org.komapper:komapper-platform:$komapperVersion").let {
        implementation(it)
        ksp(it)
    }

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("com.google.genai:google-genai:1.72.0")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.komapper:komapper-spring-boot-starter-jdbc")
    implementation("org.komapper:komapper-dialect-postgresql-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-database-postgresql")
    ksp("org.komapper:komapper-processor")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.komapper:komapper-spring-boot-starter-test-jdbc")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
            "-Xconsistent-data-class-copy-visibility",
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

ktlint {
    filter {
        exclude { it.file.path.contains("generated") }
    }
}

tasks.named<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask>("runKtlintCheckOverMainSourceSet") {
    setSource(fileTree("src/main/kotlin"))
}
