/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.common.output

import com.google.common.truth.FailureMetadata
import com.google.common.truth.IterableSubject
import com.google.common.truth.StringSubject
import com.google.common.truth.Subject

private const val NAME_ATTRIBUTE = "http://schemas.android.com/apk/res/android:name"

/**
 * Subject to test the manifest nodes on a per-node basis instead of via text comparison.
 */
@SubjectDsl
class NodeSubject(
    metadata: FailureMetadata,
    actual: Node
) : Subject<NodeSubject, Node>(metadata, actual) {

    companion object {
        internal fun nodes(): Factory<NodeSubject, Node> {
            return Factory<NodeSubject, Node> { metadata, actual ->
                NodeSubject(metadata, actual)
            }
        }
    }

    /**
     * Returns a new node subject for the matching child node by name. The node must be unique,
     * otherwise use [nodeByNameAndAttribute]
     */
    fun node(name: String): NodeSubject {
        setActualAsCurrentNode()
        // check the node exists, and is unique
        val nodeMatches = actual().children.filter { it.name == name }
        check("node($name)").that(nodeMatches).hasSize(1)

        // if we're here, the node exists and is unique as the line above throws otherwise
        return check("node($name)").about(Companion.nodes()).that(nodeMatches.single())
    }

    /**
     * Returns a new node subject for the matching child node by name, and `name` attribute.
     *
     * The name attribute that is checked against is http://schemas.android.com/apk/res/android:name
     *
     * @param nodeName the name of the node
     * @param nameAttributeValue the value of the name attribute, without enclosing quotes
     */
    fun nodeByNameAndAttribute(nodeName: String, nameAttributeValue: String): NodeSubject {
        setActualAsCurrentNode()

        // ensure the value of the nameAttribute is valid (ie does not contain quotes)
        if (nameAttributeValue.startsWith("\"")) {
            throw RuntimeException("nameAttributeValue in nodeByNameAndAttribute() should not be quoted")
        }

        // first get the nodes with the right nodeName
        val nodeByNames = actual().children.filter { it.name == nodeName }

        // now gather the value of the 'name' attributes for them.
        // this will allow us to provide a better error message.
        val nameAttributeValues = nodeByNames.mapNotNull { node ->
            node.attributes.singleOrNull { it.startsWith("$NAME_ATTRIBUTE=") }?.let { nameAttr ->
                // need to also remove the enclosing quotes
                nameAttr.substring(NAME_ATTRIBUTE.length + 2, nameAttr.length - 1)
            }
        }

        // check we have a match
        check("nodeByNameAndAttribute($nodeName, $nameAttributeValue)")
            .that(nameAttributeValues)
            .contains(nameAttributeValue)

        // Normally the line above will throw if the list does not contain the item.
        // However during test of the fixture, the line above will not interrupt the method,
        // and therefore we need to handle it
        if (!nameAttributeValues.contains(nameAttributeValue)) {
            return check("nodeByNameAndAttribute($nodeName, $nameAttributeValue)")
                .about(Companion.nodes())
                .that(NodeImpl("node_not_found"))
        }

        // now get our node match
        val nodeMatches = nodeByNames.filter { node: Node ->
            node.attributes.any { it == "$NAME_ATTRIBUTE=\"$nameAttributeValue\"" }
        }

        // make sure we only got one, which really should not happen but better to check.
        check("nodeByNameAndAttribute($nodeName, $nameAttributeValue)").that(nodeMatches).containsNoDuplicates()

        // Normally the line above will throw if the list does not contain a single item.
        // However during test of the fixture, the line above will not break, and therefore
        // we need to handle it.
        return check("nodeByNameAndAttribute($nodeName, $nameAttributeValue)")
            .about(Companion.nodes())
            .that(
                if (nodeMatches.size == 1) {
                    nodeMatches.single()
                } else {
                    // this is the test case. What we return does not matter too much,
                    // but we want to make it clear things are wrong
                    NodeImpl("broken_node_under_test")
                }
            )
    }

    /**
     * Returns a StringSubject to validate the value of an attribute.
     *
     * Note that for attribute whose values are of type string, the value must be quoted as
     * that's how it appears in the manifest.
     */
    fun attribute(name: String): StringSubject {
        val value = getAttributeValue(name)
        return check("attributeByName($name)").that(value)
    }

    /**
     * Returns the value of an attribute for the given attribute name.
     *
     * Most of the time, this should not be use, and instead [attribute] should be used
     * to validate the attribute value via a proper Truth Subject.
     *
     * There are some corner cases where values are dynamic, and we need to get it to compare
     * to other dynamic values, and this is really the only times it should be used.
     */
    fun getAttributeValue(name: String): String {
        setActualAsCurrentNode()

        // first accumulate all the attribute names only (the list contains the string as key=value)
        // This is done to provide better error message in case the attribute is missing
        // as we do this, get the value as well
        var value: String? = null
        val attributeKeys = actual().attributes.map {
            val splits = it.split('=')
            if (splits[0] == name) value = splits[1]
            splits[0]
        }
        check("attributes().contains($name)").that(attributeKeys).contains(name)

        // if we are here, then the value was found
        return value!!
    }

    /**
     * Returns an iterable subject for all the children nodes. The collection only contains the
     * node names.
     *
     * Note that there may be duplicates
     */
    fun nodes(): IterableSubject {
        setActualAsCurrentNode()
        return check("nodes()").that(actual().children.map { it.name })
    }

    fun hasNoNodes() {
        setActualAsCurrentNode()
        return check("hasNoNodes").that(actual().children).isEmpty()
    }

    /**
     * Returns an iterable subject for all the attributes.
     *
     * Each attribute is in the format `[namespace:]name=value`
     */
    fun attributes(): IterableSubject {
        setActualAsCurrentNode()
        return check("attributes()").that(actual().attributes)
    }

    /**
     * Returns the list of registered namespaces as an iterable subject
     *
     * Note that this is only valid for the root node.
     */
    fun namespaces(): IterableSubject {
        setActualAsCurrentNode()
        return check("namespaces()").that(actual().namespace)
    }

    /**
     * Sets the actual node value as the current node in the root node to impact failure facts.
     *
     * Because each subject is chained in order to have a good "value of" fact on failure
     * (showing the chain of calls from the root node), the failure will contain a "node was" fact
     * that's tied to the root node. It's not possible to intercept failures to inject
     * more "node was" fact, unless we make all testing via custom Subject which isn't worth it.
     *
     * However, when querying a sub-node, we would really like to provide as much _local_
     * information as possible on failure. Therefore, we hack around the nodes a bit, and register
     * the current node being "tested" into the root node, and the root node will return
     * that node's content in its toString.
     */
    private fun setActualAsCurrentNode() {
        (actual() as NodeImpl).setAsCurrent()
    }
}

interface Node {
    val name: String
    val children: List<Node>
    val attributes: List<String>
    val namespace: List<String>
}

internal data class NodeImpl(
    override val name: String = "",
    override val children: List<Node> = listOf(),
    override val attributes: List<String> = listOf(),
    override val namespace: List<String> = listOf(),
    private val lines: List<String> = listOf(),
): Node {

    /**
     * Link to the parent node. See [NodeSubject.setActualAsCurrentNode] for details
     */
    internal var parentNode: NodeImpl? = null
    /**
     * Link to the current node. See [NodeSubject.setActualAsCurrentNode] for details
     */
    internal var currentNode: NodeImpl? = null

    /**
     * Sets this node as the current one in the root node.
     *
     * See [NodeSubject.setActualAsCurrentNode] for details
     */
    internal fun setAsCurrent() {
        // search for the root node going up the chain
        var root = parentNode
        while (root?.parentNode != null) {
            root = root.parentNode
        }

        root?.let {
            it.currentNode = this
        }
    }

    override fun toString(): String {
        return currentNode?.let { node ->
            buildString {
                appendLine("Node(name='${node.name}')")
                appendLine(">> Node content:")
                for (line in node.lines) {
                    appendLine(" >$line")
                }
                appendLine("<< End Node content")
            }
        } ?: "Node(name='$name')"
    }
}

internal fun parseManifestToNodes(content: List<String>): Node {
    // the text content handles sub elements via indent, so parsing will go through lines and check
    // for the current indent vs the line start and decide whether to recursively handle children
    // or exist the current node.
    var expectedIndent = ""
    var index = 0

    val namespaces = mutableListOf<String>()

    while (content.checkLineFor(index, "${expectedIndent}N: ")) {
        namespaces.add(content[index].substring(expectedIndent.length + 3))
        index++
        // each new namespace is a new indent so update here
        expectedIndent = "$expectedIndent  "
    }

    // we're done with namespaces, we're now expected nodes
    // parse children nodes
    val children = mutableListOf<Node>()
    val nodeIndent = "${expectedIndent}E: "
    while (content.checkLineFor(index, nodeIndent)) {
        val result = parseNode(content, index, expectedIndent)
        children.add(result.first)
        index = result.second
    }

    // return the root node
    return NodeImpl(
        name = "rootNode",
        children = children,
        attributes = listOf(),
        namespace = namespaces,
        lines = listOf() // we don't want any content for the root node
    ).also { root ->
        children.forEach { (it as NodeImpl).parentNode = root }
    }
}

private fun parseNode(content: List<String>, startIndex: Int, indent: String): Pair<Node, Int> {
    // parse the first line which must be a node
    val nodeLine = content[startIndex]
    if (!nodeLine.startsWith("${indent}E: ")) throw RuntimeException("Expected node line but was: $nodeLine")

    val name = nodeLine.substring(indent.length + 3)

    // parse the attributes
    val attributes = mutableListOf<String>()
    var index = startIndex + 1
    val attributeIndent = "$indent  A: "
    while (content.checkLineFor(index, attributeIndent)) {
        attributes.add(content[index].substring(attributeIndent.length))
        index++
    }

    // parse children nodes
    val children = mutableListOf<Node>()
    val nodeIndent = "$indent    E: "
    while (content.checkLineFor(index, nodeIndent)) {
        val result = parseNode(content, index, "$indent    ")
        children.add(result.first)
        index = result.second
    }

    val lines = content.subList(startIndex, index)
    return NodeImpl(
        name = name,
        children = children.toList(),
        attributes = attributes.toList(),
        lines = lines
    ).also { parent ->
        children.forEach { (it as NodeImpl).parentNode = parent }
    } to index
}

private fun List<String>.checkLineFor(index: Int, prefix: String): Boolean {
    return index < size && get(index).startsWith(prefix)
}
