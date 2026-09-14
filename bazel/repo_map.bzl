"""Generates files mapping apparent repository names to canonical repository names."""

def _repo_map_properties_impl(ctx):
    filename = ctx.attr.filename or (ctx.label.name + ".properties")
    out = ctx.actions.declare_file(filename)
    content = []
    for repo in ctx.attr.repos:
        content.append("%s=%s\n" % (repo, Label("@" + repo).repo_name))
    ctx.actions.write(out, "".join(content))
    return [DefaultInfo(files = depset([out]))]

repo_map_properties = rule(
    doc = """Generates a Java .properties file mapping apparent repository names to canonical repository names.""",
    implementation = _repo_map_properties_impl,
    attrs = {
        "repos": attr.string_list(
            mandatory = True,
            doc = "List of apparent repository names to map to their canonical names.",
        ),
        "filename": attr.string(
            doc = "Optional output file name. Defaults to '<target_name>.properties'.",
        ),
    },
)
