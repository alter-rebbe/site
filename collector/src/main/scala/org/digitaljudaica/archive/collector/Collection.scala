package org.digitaljudaica.archive.collector

import java.io.File
import org.digitaljudaica.archive.collector.reference.Reference
import org.digitaljudaica.util.{Collections, Files}
import org.digitaljudaica.xml.{ContentType, Element, From, Parser, Xml, XmlUtil}
import Table.Column
import org.digitaljudaica.tei.Tei
import scala.xml.{Elem, Node, Text}

final class Collection private(
  layout: Layout,
  val directoryName: String,
  sourceDirectory: File,
  isBook: Boolean,
  val publish: Boolean,
  val archive: Option[String],
  val prefix: Option[String],
  val number: Option[Int],
  titleNodes: Option[Seq[Node]],
  val caseAbstract: Seq[Node],
  val description: Seq[Node],
  partDescriptors: Seq[Part.Descriptor]
) extends CollectionLike with Ordered[Collection] {

  override def toString: String = directoryName

  val parts: Seq[Part] = Part.Descriptor.splitParts(partDescriptors, getDocuments(sourceDirectory))

  def archiveCase: String = prefix.getOrElse("") + number.map(_.toString).getOrElse("")

  override def reference: String = archive.fold(archiveCase)(archive => archive + " " + archiveCase)

  def title: Node = titleNodes.fold[Node](Text(reference))(nodes => <title>{nodes}</title>)

  override def compare(that: Collection): Int = {
    val archiveComparison: Int = compare(archive, that.archive)
    if (archiveComparison != 0) archiveComparison else {
      val prefixComparison: Int = compare(prefix, that.prefix)
      if (prefixComparison != 0) prefixComparison else {
        number.getOrElse(0).compare(that.number.getOrElse(0))
      }
    }
  }

  // TODO where is this in the standard library?
  private def compare(a: Option[String], b: Option[String]): Int = {
    if (a.isEmpty && b.isEmpty) 0
    else if (a.isEmpty) -1
    else if (b.isEmpty) 1
    else a.get.compare(b.get)
  }

  def pageType: Page.Type = if (isBook) Page.Book else Page.Manuscript

  val documents: Seq[Document] = parts.flatMap(_.documents)

  def references: Seq[Reference] = documents.flatMap(_.references)

  private val pages: Seq[Page] = documents.flatMap(_.pages)

  val missingPages: Seq[String] = pages.filterNot(_.isPresent).map(_.displayName)

  /// Check consistency
  checkPages()

  private def checkPages(): Unit = {
    // TODO with images on a separate website (facsimiles.alter-rebbe.org), this has to be re-worked...
//    // Check that all the images are accounted for
//    val imageNames: Set[String] =
//      Util.filesWithExtensions(
//        directory = layout.facsimiles(directory),
//        ".jpg"
//      )
//      .toSet
//    imageNames.foreach(name => pageType(name, isPresent = true))
//
//    val usedImages: Set[String] = pages.filter(_.isPresent).map(_.name).toSet
//    val orphanImages: Seq[String] = (imageNames -- usedImages).toSeq.sorted
//    val missingImages: Seq[String] = (usedImages -- imageNames).toSeq.sorted
//    if (orphanImages.nonEmpty) throw new IllegalArgumentException(s"Orphan images: $orphanImages")
//    if (missingImages.nonEmpty)
//      throw new IllegalArgumentException(s"Missing images: $missingImages")
  }

  private def splitLang(name: String): (String, Option[String]) = {
    val dash: Int = name.lastIndexOf('-')
    if ((dash == -1) || (dash != name.length-3)) (name, None)
    else (name.substring(0, dash), Some(name.substring(dash+1)))
  }

  private def getDocuments(sourceDirectory: File): Seq[Document] = {
    val namesWithLang: Seq[(String, Option[String])] =
      Files.filesWithExtensions(sourceDirectory, "xml").sorted.map(splitLang)

    val translations: Map[String, Seq[String]] = Collections.mapValues(namesWithLang
      .filter(_._2.isDefined)
      .map(e => (e._1, e._2.get))
      .groupBy(_._1))(_.map(_._2))

    val names: Seq[String] = namesWithLang.filter(_._2.isEmpty).map(_._1)

    val namesWithSiblings: Seq[(String, (Option[String], Option[String]))] = if (names.isEmpty) Seq.empty else {
      val documentOptions: Seq[Option[String]] = names.map(Some(_))
      val prev = None +: documentOptions.init
      val next = documentOptions.tail :+ None
      names.zip(prev.zip(next))
    }

    for ((name, (prev, next)) <- namesWithSiblings) yield new Document(
      layout,
      collection = this,
      tei = Parser.parseDo(Tei.parse(From.file(sourceDirectory, name))),
      name,
      prev,
      next,
      translations = translations.getOrElse(name, Seq.empty)
    )
  }
}

object Collection {

  def parser(
    layout: Layout,
    directory: File
  ): Parser[Collection] = for {
    isBook <- Xml.attribute.optional.booleanOrFalse("isBook")
    publish <- Xml.attribute.optional.booleanOrFalse("publish")
    archive <- Xml.text.optional("archive")
    prefix <- Xml.text.optional("prefix")
    numberStr <- Xml.text.optional("number") // TODO text.int
    titleNodes <- Element("title", ContentType.Mixed, Xml.allNodes).optional // TODO common combinator
    caseAbstract <- Element("abstract", ContentType.Mixed, Xml.allNodes).required // TODO common combinator
    notes <- Element("notes", ContentType.Mixed, Xml.allNodes).optional // TODO common combinator
    description = Seq(<span>{caseAbstract}</span>) ++ notes.getOrElse(Seq.empty)
    // TODO swap parts and notes; remove notes wrapper element; simplify parts; see how to generalize parts...
    partDescriptors <- Element("part", ContentType.Elements, Part.Descriptor.parser).all
  } yield new Collection(
    layout,
    directoryName = directory.getName,
    sourceDirectory = layout.tei(directory),
    isBook,
    publish,
    archive,
    prefix,
    number = numberStr.map(_.toInt),
    titleNodes,
    caseAbstract,
    description,
    partDescriptors
  )

  def table(documentUrlRelativeToIndex: String => String): Table[Document] = new Table[Document](
    Column("Описание", "description", { document: Document =>
      document.description.getOrElse(Seq.empty).map(XmlUtil.removeNamespace)
    }),

    Column("Дата", "date", { document: Document =>
      document.date.fold[Seq[Node]](Text(""))(value => Text(value))
    }),

    Column("Кто", "author", { document: Document =>
      multi(document.authors.flatMap(_.map(XmlUtil.removeNamespace)))
    }),

    Column("Кому", "addressee",  _.addressee.fold[Seq[Node]](Text(""))(addressee =>
      <persName ref={addressee.ref.orNull}>{addressee.name}</persName>)),

    Column("Язык", "language", { document: Document =>
      val translations: Seq[Elem] = for (translation <- document.translations) yield
        <ref target={documentUrlRelativeToIndex(document.name + "-" + translation)}
             role="documentViewer">{translation}</ref>

      Seq(Text(document.language.getOrElse("?"))) ++ translations
    }),

    Column("Документ", "document", { document: Document =>
      <ref target={documentUrlRelativeToIndex(document.name)}
           role="documentViewer">{document.name}</ref>
    }),

    Column("Страницы", "pages", { document: Document => for (page <- document.pages) yield
      <ref target={documentUrlRelativeToIndex(document.name) + s"#p${page.n}"}
           role="documentViewer"
           rendition={if (page.isPresent) "page" else "missing-page"}>{page.displayName}</ref>
    }),

    Column("Расшифровка", "transcriber", { document: Document =>
      multi(document.transcribers.map(XmlUtil.removeNamespace))
    })
  )

  private def multi(nodes: Seq[Node]): Seq[Node] = nodes match {
    case Nil => Nil
    case n :: Nil => Seq(n)
    case n :: ns if n.isInstanceOf[Elem] => Seq(n, Text(", ")) ++ multi(ns)
    case n :: ns => Seq(n) ++ multi(ns)
    case n => n
  }
}
