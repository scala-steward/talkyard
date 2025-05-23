/**
 * Copyright (c) 2012-2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package talkyard.server.http

import com.debiki.core._
import com.debiki.core.Prelude._
import controllers.Utils.ValidationImplicits._
import debiki._
import debiki.EdHttp._
import debiki.dao.SiteDao
import talkyard.server.security.{SidStatus, XsrfOk, BrowserId}
import play.api.mvc.Request



/** A page related request.
  *
  * Naming convention: Functions that assume that the page exists, and throws
  * a 404 Not Found error otherwise, are named like "thePage" or "thePageParts",
  * whilst functions that instead require an Option are named simply "page" or
  * "pageParts".
  */
class PageRequest[A](
  val site: SiteBrief,
  val anyTySession: Opt[TySession],
  val sid: SidStatus,
  val xsrfToken: XsrfOk,
  val browserId: Option[BrowserId],
  val user: Option[Participant],
  val pageExists: Boolean,
  /** Ids of groups to which the requester belongs. */
  // userMemships: List[String],
  /** If the requested page does not exist, pagePath.pageId is empty. */
  val pagePath: PagePath,
  val pageMeta: Option[PageMeta],
  val ancCatsRootLast: ImmSeq[Cat],
  val embeddingUrl: Option[String],
  val altPageId: Option[String],
  val dao: SiteDao,
  val request: Request[A]) extends DebikiRequest[A] {

  require(pagePath.siteId == tenantId) //COULD remove tenantId from pagePath
  require(!pageExists || pagePath.pageId.isDefined)
  require(!pageExists || pageMeta.isDefined)

  pageMeta foreach { meta =>
    require(pagePath.pageId contains meta.pageId)
  }


  def pageId: Option[PageId] = pagePath.pageId
  def theSitePageId: SitePageId = SitePageId(siteId, thePageId)

  /**
   * Throws 404 Not Found if id unknown. The page id is known if it
   * was specified in the request, *or* if the page exists.
   */
  def thePageId : PageId = pagePath.pageId getOrElse
    throwNotFound("DwE93kD4", "Page does not exist: "+ pagePath.value)


  /**
   * The page root tells which post to start with when rendering a page.
   * By default, the page body is used. The root is specified in the
   * query string, like so: ?view=rootPostId  or ?edit=....&view=rootPostId
   */
  lazy val pageRoot: AnyPageRoot =
    request.queryString.get("view").map(rootPosts => rootPosts.size match {
      case 1 => Some(parseIntOrThrowBadReq(rootPosts.head))
      // It seems this cannot hapen with Play Framework:
      case 0 => die("TyE03kI8", "Query string param with no value")
      case _ => throwBadReq("DwE0k35", "Too many `view' query params")
    }) getOrElse {
      pageRole match {
        case Some(PageType.EmbeddedComments) =>
          // There's no page body that can be used as page root, because embedded
          // pages contain comments only.
          None
        case _ =>
          DefaultPageRoot
      }
    }


  def pageRole: Option[PageType] = pageMeta.map(_.pageType)

  def thePageRole : PageType = thePageMeta.pageType

  def thePageMeta: PageMeta = pageMeta getOrElse throwNotFound(
    "DwE3ES58", s"No page meta found, page id: $pageId")


  CLEAN_UP; REMOVE /* Use DiscProps intstead.  [2D_LAYOUT]
  lazy val thePageSettings: EffectiveSettings = {
    if (false) { // pageExists) {
      ??? // dao.loadSinglePageSettings(thePageId)
    }
    /* Now when using categories instead of category pages, should I load settings
       for the categories somehow? Currently there are none though, so just skip this.
    else if (theParentPageId.isDefined) {
      dao.loadPageTreeSettings(theParentPageId.get)
    } */
    else {
      this.siteSettings  // same as old code: dao.getWholeSiteSettings()
    }
  } */


  /** For rendering a chat somewhere in the middle of the chat — e.g. if following a
    * search result link, then we'd want to render a few messages before and after
    * the search result chat message. Or if following a bookmark link to a chat message.
    */
  private def anyOffset: Opt[i32] =
    request.queryString.getFirst("offset") map { intStr: St =>
      intStr.toIntOption getOrThrowBadArg("TyEOFS0INT", "offest", s"not an int: $intStr")
    }


  def renderParams: PageRenderParams = {
    val discProps = DiscProps.derive(
          selfSource = pageMeta,
          ancestorSourcesSpecificFirst = ancCatsRootLast,
          defaults = siteSettings.discPropsFor(
                // If props per page type, and the page doesn't yet exist, then,
                // later, we'll show a Create-page-here question?  But for now:
                pageMeta.map(_.pageType).getOrElse(PageType.Discussion)))
    PageRenderParams(
          discProps.comtOrder,
          //discProps.comtNesting — later
          comtOffset = anyOffset,
          widthLayout = if (isMobile) WidthLayout.Tiny else WidthLayout.Medium,
          isEmbedded = embeddingUrl.nonEmpty,
          origin = origin,
          anyCdnOrigin = dao.globals.anyCdnOrigin,
          anyUgcOrigin = dao.globals.anyUgcOriginFor(site),
          anyPageRoot = pageRoot,
          anyPageQuery = parsePageQuery())
  }


  /** If we should include comment vote and read count statistics in the html.
    */
  def debugStats: Boolean =
    request.queryString.getEmptyAsNone("debugStats").contains("true")


  /** In Prod mode only staff can bypass the cache, otherwise it'd be a bit too easy
    * to DoS attack the server. SECURITY COULD use a magic config file password instead.
    */
  def bypassCache: Boolean =
    (!Globals.isProd || user.exists(_.isStaff)) &&
      request.queryString.getEmptyAsNone("bypassCache").contains("true")

}


/** A request from a page that you provide manually (the page won't be loaded
  * from the database). EmbeddedTopicsController constructs an empty dummy page
  * when showing comments for an URL for which no page has yet been created.
  */
class DummyPageRequest[A](
  siteIdAndCanonicalHostname: SiteBrief,
  anyTySession: Opt[TySession],
  sid: SidStatus,
  xsrfToken: XsrfOk,
  browserId: Option[BrowserId],
  user: Option[Participant],
  pageExists: Boolean,
  pagePath: PagePath,
  pageMeta: PageMeta,
  ancCatsRootLast: ImmSeq[Cat],
  dao: SiteDao,
  request: Request[A]) extends PageRequest[A](
    siteIdAndCanonicalHostname, anyTySession, sid, xsrfToken, browserId, user, pageExists,
    pagePath, Some(pageMeta), ancCatsRootLast, altPageId = None, embeddingUrl = None,
    dao = dao, request = request) {

}
