package org.gradle.build.docs

import org.w3c.dom.Element
import org.w3c.dom.Node

class BuildableDOMCategory {
    public static setText(Element element, String value) {
        while (element.hasChildNodes()) {
            element.removeChild(element.getFirstChild())
        }
        element.appendChild(element.ownerDocument.createTextNode(value))
    }

    public static setChildren(Element element, Closure cl) {
        while (element.hasChildNodes()) {
            element.removeChild(element.getFirstChild())
        }
        leftShift(element, cl)
    }

    public static leftShift(Element parent, Closure cl) {
        DomBuilder builder = new DomBuilder(parent)
        cl.delegate = builder
        cl.call()
    }

    public static leftShift(Element parent, Node node) {
        parent.appendChild(parent.ownerDocument.importNode(node, true))
    }

    public static addFirst(Element parent, Closure cl) {
        DomBuilder builder = new DomBuilder(parent.ownerDocument, null)
        cl.delegate = builder
        cl.call()
        def firstChild = parent.firstChild
        builder.elements.each { element ->
            parent.insertBefore(element, firstChild)
        }
    }

    public static addBefore(Element sibling, Closure cl) {
        DomBuilder builder = new DomBuilder(sibling.ownerDocument, null)
        cl.delegate = builder
        cl.call()
        def parent = sibling.parentNode
        builder.elements.each { element ->
            parent.insertBefore(element, sibling)
        }
    }

    public static addAfter(Element sibling, Closure cl) {
        DomBuilder builder = new DomBuilder(sibling.ownerDocument, null)
        cl.delegate = builder
        cl.call()
        def parent = sibling.parentNode
        builder.elements.each { element ->
            parent.insertBefore(element, sibling.nextSibling)
        }
    }
}
