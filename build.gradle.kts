plugins {
    base
}

tasks.register<Copy>("installDist") {
    dependsOn(":kerosene-jctl:installDist")
    from(project(":kerosene-jctl").layout.buildDirectory.dir("install/kerosene-jctl"))
    into(layout.buildDirectory.dir("install/kerosene-jctl"))
}

tasks.register("test") {
    dependsOn(":kerosene-jctl:test")
}
