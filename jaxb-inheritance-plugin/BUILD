load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "jaxb-inheritance-plugin",
    srcs = glob(["src/main/java/**"]),
    resources = glob(["src/main/resources/**"]),
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/annotations",
        "@maven//:com.google.guava.guava",
        "@maven//:org.glassfish.jaxb.jaxb-xjc",
    ],
)
