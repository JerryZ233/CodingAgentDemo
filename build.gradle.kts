plugins {
    id("java")
    id("application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.demo.agent.Main")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
