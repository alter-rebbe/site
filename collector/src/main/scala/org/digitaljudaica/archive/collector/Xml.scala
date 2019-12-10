package org.digitaljudaica.archive.collector

import java.io.File

import scala.xml.transform.{RewriteRule, RuleTransformer}
import scala.xml.{Elem, Node, PrettyPrinter, Text, TopScope, Utility, XML}

object Xml {

  def load(directory: File, fileName: String): Elem = {
    val file: File = new File(directory, fileName + ".xml")
    try {
      Utility.trimProper(XML.loadFile(file)).asInstanceOf[Elem]
    } catch {
      case e: org.xml.sax.SAXParseException =>
        throw new IllegalArgumentException(s"In file $file:", e)
    }
  }

  private def removeNamespace(node: Node): Node = node match {
    case e: Elem => e.copy(scope = TopScope, child = e.child.map(removeNamespace))
    case n => n
  }

  private def removeNamespace(element: Elem): Elem =
    element.copy(scope = TopScope, child = element.child.map(removeNamespace))

  def spacedText(node: Node): String = node match {
    case elem: Elem => (elem.child map (_.text)).mkString(" ")
    case node: Node => node.text
  }

  def rewriteElements(xml: Elem, elementRewriter: Elem => Elem): Elem = {
    val rule: RewriteRule = new RewriteRule {
      override def transform(node: Node): Seq[Node] = node match {
        case element: Elem => elementRewriter(element)
        case other => other
      }
    }

    new RuleTransformer(rule).transform(xml).head.asInstanceOf[Elem]
  }


  implicit class Ops(elem: Elem) {

    def elems(name: String): Seq[Elem] = {
      val result = elem.elements
      result.foreach(_.check(name))
      result
    }

    def elemsFilter(name: String): Seq[Elem] = elem.elements.filter(_.label == name)

    def elements: Seq[Elem] = elem.child.filter(_.isInstanceOf[Elem]).map(_.asInstanceOf[Elem])

    def descendants(name: String): Seq[Elem] = elem.flatMap(_ \\ name).filter(_.isInstanceOf[Elem]).map(_.asInstanceOf[Elem])

    def getAttribute(name: String): String = attributeOption(name).getOrElse(throw new NoSuchElementException(s"No attribute $name"))

    def attributeOption(name: String): Option[String] = elem.attributes.asAttrMap.get(name)

    def idOption: Option[String] = attributeOption("xml:id")

    def id: String = getAttribute("xml:id")

    def intAttributeOption(name: String): Option[Int] = attributeOption(name).map { value =>
      try { value.toInt } catch { case e: NumberFormatException => throw new IllegalArgumentException(s"$value is not a number", e)}
    }

    def intAttribute(name: String): Int = intAttributeOption(name).getOrElse(throw new NoSuchElementException(s"No attribute $name"))

    def booleanAttribute(name: String): Boolean = {
      val value = attributeOption(name)
      value.isDefined && ((value.get == "true") || (value.get == "yes"))
    }

    def oneChild(name: String): Elem = oneOptionalChild(name, required = true).get
    def optionalChild(name: String): Option[Elem] = oneOptionalChild(name, required = false)

    private[this] def oneOptionalChild(name: String, required: Boolean = true): Option[Elem] = {
      val children = elem \ name

      if (children.size > 1) throw new NoSuchElementException(s"To many children with name '$name'")
      if (required && children.isEmpty) throw new NoSuchElementException(s"No child with name '$name'")

      if (children.isEmpty) None else Some(children.head.asInstanceOf[Elem])
    }

    def check(name: String): Elem = {
      if (elem.label != name) throw new NoSuchElementException(s"Expected name $name but got $elem.label")
      elem
    }

    def withoutNamespace: Elem = removeNamespace(elem)

    def format: String = Xml.format(elem)
  }

  private val prettyPrinter: PrettyPrinter = new PrettyPrinter(120, 2)

  private val join: Set[String] = Set(".", ",", ";", ":", "\"", ")")

  def format(elem: Elem): String = {
    @scala.annotation.tailrec
    def merge(result: List[String], lines: List[String]): List[String] = lines match {
      case l1 :: l2 :: ls =>
        val l = l2.trim
        if (join.exists(l.startsWith))
          merge(result, (l1 ++ l) :: ls)
        else
          merge(result :+ l1, l2 :: ls)
      case l :: Nil => result :+ l
      case Nil => result
    }

    val result: String = prettyPrinter.format(elem)

    // pretty-printer splits punctuation from the preceding elements; merge them back :)
    merge(List.empty, result.split("\n").toList).mkString("\n")
  }
}
