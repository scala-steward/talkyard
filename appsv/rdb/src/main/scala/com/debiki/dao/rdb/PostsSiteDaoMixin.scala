/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
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

package com.debiki.dao.rdb

import scala.collection.Seq
import collection.immutable
import collection.mutable.ArrayBuffer
import com.debiki.core._
import com.debiki.core.isProd
import com.debiki.core.PageParts.TitleNr
import com.debiki.core.Prelude._
import java.{sql => js}
import Rdb._
import RdbUtil._
import PostsSiteDaoMixin._


/** Loads and saves posts.
  */
trait PostsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteTransaction =>

  override def loadPost(uniquePostId: PostId): Option[Post] =
    loadPostsById(Seq(uniquePostId)).headOption


  override def loadPost(pageId: PageId, postNr: PostNr): Option[Post] =
    loadPostsOnPageImpl(pageId, postNr = Some(postNr), None).headOption


  override def loadPostsOnPage(pageId: PageId, which: WhichPostsOnPage): Vec[Post] =
    loadPostsOnPageImpl(pageId, postNr = None, Some(which))

  private def select__posts_po__someJoin__patPostRels_pa(someJoin: St): St = s"""
        select po.*,
               -- Builds an array of arrays:
               --    [[rel-type, from-pat], [other-rel-type, other-pat], ...]
               array_agg(array[pa.rel_type_c, pa.from_pat_id_c])
                    filter (where pa.rel_type_c is not null)
                    pat_post_rels_array
        from posts3 po
        $someJoin post_actions3 pa
            on  pa.site_id = po.site_id
            and pa.to_post_id_c = po.unique_post_id  """

  private val and__pa_relType__in__aut_own_asg: St = s"""
            and pa.rel_type_c in (${
                PatNodeRelType.AuthorOf_later.toInt}, ${
                PatNodeRelType.OwnerOf_later.toInt}, ${
                PatNodeRelType.AssignedTo.toInt}) """

  private val select__posts_po__leftJoin__patPostRels_pa: St =
    select__posts_po__someJoin__patPostRels_pa("left outer join") +
          and__pa_relType__in__aut_own_asg

  private val select__posts_po__innerJoin__patPostRels_pa: St =
    select__posts_po__someJoin__patPostRels_pa("inner join") +
          and__pa_relType__in__aut_own_asg

  // It's enough to specify the posts3 primary key.
  private val groupBy__siteId_postId: St = s"""
          group by po.site_id, po.unique_post_id """

  private val groupBy_orderBy__siteId_postId: St = s"""
          $groupBy__siteId_postId
          order by po.site_id, po.unique_post_id """

  private val and__po_approved_at__is_not_null = "and po.approved_at is not null"


  private def loadPostsOnPageImpl(pageId: PageId, postNr: Opt[PostNr],
          which: Opt[WhichPostsOnPage]): Vec[Post] = {
    // Similar to:  loadPostsByNrs(_: Iterable[PagePostNr])

    dieIf(postNr.isDefined && which.isDefined, "TyE7SGJ3Q", "Both post nr & which")

    val values = ArrayBuffer[AnyRef](siteId.asAnyRef, pageId)

    val andPostNrEq = postNr map { id =>
      values.append(id.asAnyRef)
      "and po.post_nr = ?"
    } getOrElse ""

    val andPostNrGtOrLt = which match {
      case None =>
        // Then we're loading specific posts by nr, don't need another filter.
        ""
      case Some(which2) => which2 match {
        case _: WhichPostsOnPage.OnlyPublic =>
          s"and  po.post_nr >= ${PageParts.MinPublicNr}"
        case priv: WhichPostsOnPage.OnlyPrivate =>
          // Later: Need a way to specify & load private-posts-visible-to somebody. [priv_comts]
          // Maybe we'll need to [load_all_private_comments] on pageId.
          // But for now, this works for bookmarks.
          // If:  activeOnly = true, mustBeApproved = Some(false),
          // then is ame as:  loadUnapprovedPosts(allPublic = false, ...)?
          // except that the latter loads hidden posts, this one doesn't, but
          // doesn't matter since bookmarks & priv comments can't be hidden?
          values.append(priv.byUserId.asAnyRef)
          s"and  po.post_nr <= ${PageParts.MaxPrivateNr}  and po.created_by_id = ?"
        case range: WhichPostsOnPage.TopLevelRange =>
          // Posts with lower nrs were added first — they're "older'.
          val (start, end) = range.direction match {
            case RangeDir.Older => (range.offset - range.limit + 1, range.offset)
            case RangeDir.Newer => (range.offset, range.offset + range.limit - 1)
            case RangeDir.Around =>
              // If TopLevelRange.limit is 25:  25 / 2 = 12, so we'll load the post nr
              // at `range.offset` + 12 older and 12 newer, that's 25 in total.
              val halfLim = range.limit / 2
              (range.offset - halfLim, range.offset + halfLim)
          }
          // Load from incl start.  But the title and orig post should already have been
          // loaded. Negative nr are private posts (e.g. bookmarks, private comments) and
          // have random nrs, so, off-topic here.
          values.append(Math.max(start, PageParts.FirstReplyNr).asAnyRef)
          // Shouldn't try to load private posts, or title or body.
          dieIf(end < PageParts.FirstReplyNr, "TyE602SKL9", s"Bad scroll offset: end = ${
                end} < FirstReplyNr, range: $range")
          // Load up to incl end:
          values.append(end.asAnyRef)
          s"and  po.post_nr  between ? and ?"
        case _: WhichPostsOnPage.AllByAnyone =>
           "" // all
      }
    }

    val mustBeApproved = which map { which2 =>
            which2.mustBeApproved getOrElse which2.activeOnly  // [load_only_approved]
          } getOrElse {
            // If loading a specific post nr, it's wanted, approved or not approved.
            false
          }

    COULD_OPTIMIZE // Don't load others' unapproved comments. Filtered out later, but better
    // do here directly? [careful_cache_range]
    val andApproved =
          if (mustBeApproved) and__po_approved_at__is_not_null
          else ""

    val andNotHiddenDeleted = if (!which.exists(_.activeOnly)) "" else
            s"""and  po.hidden_at  is null
                and  po.deleted_status = ${DeletedStatus.NotDeleted.toInt} """

    val query = s""" -- loadPostsOnPageImpl
          $select__posts_po__leftJoin__patPostRels_pa
          where po.site_id = ? and
                po.page_id = ?
                $andPostNrEq
                $andPostNrGtOrLt
                $andApproved
                $andNotHiddenDeleted
          $groupBy_orderBy__siteId_postId """

    runQueryFindMany(query, values.toList, rs => {
      readPost(rs, pageId = Some(pageId))
    })
  }


  // [to_paginate]
  override def loadOrigPostAndLatestPosts(pageId: PageId, limit: Int): Seq[Post] = {
    require(limit > 0, "EsE7GK4W0")
    // Use post_nr, not created_at, because 1) if a post is moved from another page to page pageId,
    // then its created_at might be long-ago, although its post_nr will become the highest
    // on this page once it's been added. Also 2) there's an index on pageid, post_nr.

    val query = s""" -- loadOrigPostAndLatestPosts
        -- Load title and orig post
        $select__posts_po__leftJoin__patPostRels_pa
        where po.site_id = ? and
              po.page_id = ? and
              po.post_nr in (${PageParts.TitleNr}, ${PageParts.BodyNr})
        $groupBy__siteId_postId
        union
        select * from (
            -- Load latest posts
            $select__posts_po__leftJoin__patPostRels_pa
            where po.site_id = ? and
                  po.page_id = ? and
                  -- Skip title & body (loaded above) and private posts e.g. bookmarks.
                  po.post_nr >= ${PageParts.FirstReplyNr}
            $groupBy__siteId_postId
            order by post_nr desc limit $limit
            ) required_subquery_alias  """

    runQueryFindMany(query, List(siteId.asAnyRef, pageId.asAnyRef, siteId.asAnyRef,
        pageId.asAnyRef), rs => {
      readPost(rs, pageId = Some(pageId))
    })
  }


  private def loadPostsById(postIds: Iterable[PostId]): immutable.Seq[Post] = {
    if (postIds.isEmpty)
      return Nil
    val values = ArrayBuffer[AnyRef](siteId.asAnyRef)
    val queryBuilder = new StringBuilder(1024, s""" -- loadPostsById
       $select__posts_po__leftJoin__patPostRels_pa
       where po.site_id = ? and (""")
    var first = true
    for (postId <- postIds) {
      if (first) first = false
      else queryBuilder.append(" or ")
      queryBuilder.append("po.unique_post_id = ?")
      values.append(postId.asAnyRef)
    }
    queryBuilder.append(s""")
          $groupBy_orderBy__siteId_postId
          """)
    var results = ArrayBuffer[Post]()
    runQuery(queryBuilder.toString, values.toList, rs => {
      while (rs.next()) {
        val post = readPost(rs)
        results += post
      }
    })
    results.to(immutable.Seq)
  }


  def loadPostsByNrs(pagePostNrs: Iterable[PagePostNr]): immutable.Seq[Post] = {
    // Similar to: loadPostsOnPageImpl(pageId, postNr)
    if (pagePostNrs.isEmpty)
      return Nil

    val values = ArrayBuffer[AnyRef](siteId.asAnyRef)
    val queryBuilder = new StringBuilder(1024,
          s""" -- loadPostsByNrs
          $select__posts_po__leftJoin__patPostRels_pa
          where po.site_id = ? and (""")
    var nr = 0
    for (pagePostNr: PagePostNr <- pagePostNrs.toSet) {
      if (nr >= 1) queryBuilder.append(" or ")
      nr += 1
      queryBuilder.append("(po.page_id = ? and po.post_nr = ?)")
      values.append(pagePostNr.pageId, pagePostNr.postNr.asAnyRef)
    }
    queryBuilder.append(s""")
          $groupBy__siteId_postId
          """)

    runQueryFindMany(queryBuilder.toString, values.toList, rs => {
      readPost(rs)
    })
  }


  def loadPostsByIdKeepOrder(postIds: Iterable[PostId]): ImmSeq[Post] = {
    val byId = loadPostsByUniqueId(postIds)
    postIds.flatMap(byId.get).toVector
  }


  def loadPostsByUniqueId(postIds: Iterable[PostId]): immutable.Map[PostId, Post] = {
    loadPostsBySomeId("unique_post_id", postIds, _.id)
  }


  def loadPostsByExtIdAsMap(extImpIds: Iterable[ExtId]): immutable.Map[ExtId, Post] = {
    loadPostsBySomeId("ext_id", extImpIds, _.extImpId.getOrDie("TyE2GKGCU7L"))
  }


  private def loadPostsBySomeId[T](fieldName: String, someIds: Iterable[T], getId: Post => T)
        : immutable.Map[T, Post] = {
    if (someIds.isEmpty)
      return Map.empty

    // Would need to join with pat_node_rels_t.
    dieIf(fieldName == "created_by_id", "TyE603MRKJ")

    val query = i""" -- loadPostsBySomeId
      $select__posts_po__leftJoin__patPostRels_pa
      where po.site_id = ? and po.$fieldName in (${makeInListFor(someIds)})
      $groupBy__siteId_postId
      """
    val values = siteId.asAnyRef :: someIds.map(_.asAnyRef).toList
    runQueryBuildMap(query, values, rs => {
      val post = readPost(rs)
      getId(post) -> post
    })
  }


  def loadAllPostsForExport(): immutable.Seq[Post] = {
    // Since this is for including in a site dump, we don't need the pat-to-node
    // relationships here. Instead, they'll be exported separately.
    val query = i""" -- loadAllPostsForExport
      select * from posts3 po
      where po.site_id = ?
      """
    runQueryFindMany(query, List(siteId.asAnyRef), rs => {
      readPost(rs, pageId = None, inclAggs = false)
    })
  }


  def loadUnapprovedPosts(pageId: PageId, ownBy: PatId, allPublic: Bo = false,
          limit: i32): immutable.Seq[Post] = {

    // Later: Do we ever want to use this for loading private comments? [priv_comts]
    // Maybe theoretically if  loadPostsOnPageImpl()  [load_all_private_comments]  won't
    // load all private comments because there's too many (a billion!), then we could use
    // this fn to load the current user's *own* private comments, which would be
    // more important to han.

    // If we're also loading *all* public unapproved posts, no need
    // to also load our *own* public unapproved posts. Only our own private.
    val andIsPrivate =
          if (allPublic) s"and po.post_nr <= ${PageParts.MaxPrivateNr}"
          else ""

    // If admins want to see deleted posts, or one wants to see one's own deleted
    // posts, the last `po.deleted_status` test needs to be dynamically changed to
    // `or deleted_status <> ...` and some reordering and parenthesis.  [opt_show_deld_posts]
    // Then, rename this fn to: loadUnapprovedAndDeletedPosts ?
    val andNotFormOrDeleted = s"""
              and (po.type is null or po.type not in (
                    -- Form submissions don't currently need to be approved
                    -- (aren't part of any discussion).
                    ${PostType.CompletedForm.toInt}))
              -- Load unapproved hidden posts, right. [_load_hidden]
              -- But not deleted:
              and po.deleted_status = ${DeletedStatus.NotDeleted.toInt}  """

    val values = ArrayBuffer[AnyRef](
          siteId.asAnyRef, pageId.asAnyRef, ownBy.asAnyRef)

    // One's own unapproved comments, but also one's bookmarks (they're posts too, unapproved).
    val ownUnapprovedQuery = s"""
          $select__posts_po__leftJoin__patPostRels_pa
          where po.site_id = ?
              and po.page_id = ?
              and po.approved_at is null
              -- Only our own.
              and po.created_by_id = ?  -- pat_node_rels_t [AuthorOf]
              -- Only private, if loading all public in the union query below.
              $andIsPrivate
              $andNotFormOrDeleted
          $groupBy__siteId_postId """

    // For moderators, so they can see & review posts pending approval.
    val unionPublicUnapprovedQuery = if (!allPublic) "" else {
      values.append(siteId.asAnyRef, pageId.asAnyRef)
      s"""
          union
          $select__posts_po__leftJoin__patPostRels_pa
          where po.site_id = ?
              and po.page_id = ?
              and po.approved_at is null
              -- Only public (exclude bookmarks and private comments).
              and po.post_nr >= ${PageParts.MinPublicNr}
              $andNotFormOrDeleted
          $groupBy__siteId_postId """
    }

    val query = s""" -- loadUnapprovedPosts
          $ownUnapprovedQuery
          $unionPublicUnapprovedQuery
          -- Order the total result, after union. (Incl id, for predictable e2e tests.)
          order by created_at desc, unique_post_id desc limit $limit """

    runQueryFindMany(query, values.toList, rs => {
      readPost(rs, pageId = Some(pageId))
    })
  }


  def loadCompletedForms(pageId: PageId, limit: Int): immutable.Seq[Post] = {
    val query = s""" -- loadCompletedForms
      $select__posts_po__leftJoin__patPostRels_pa
      where po.site_id = ?
        and po.page_id = ?
        and po.type = ${PostType.CompletedForm.toInt}
      $groupBy__siteId_postId
      order by po.created_at desc
      limit $limit
      """
    runQueryFindMany(query, List(siteId.asAnyRef, pageId.asAnyRef), rs => {
      readPost(rs, pageId = Some(pageId))
    })
  }


  def loadBookmarksAndBookmarkedPosts(byPatId: PatId,
            limit: i32, offsetAt: When, offsetId: PostId)
            : (ImmSeq[Post], ImmSeq[Post]) = {

    val values = List(siteId.asAnyRef, byPatId.asAnyRef)
    val query = s""" -- loadBookmarksAndBookmarkedPosts
          with bms as (
            $select__posts_po__leftJoin__patPostRels_pa
            where  po.site_id =  ?
              and  po.created_by_id = ?
              and  po.type = ${PostType.Bookmark.toInt}
              and  po.deleted_at is null
            $groupBy__siteId_postId
            order by po.created_at desc
            limit $limit
          )
          -- Bookmarks
          select * from bms
          union
          -- Bookmarked posts
          $select__posts_po__leftJoin__patPostRels_pa
          inner join  bms
             on   bms.site_id       =  po.site_id
             and  bms.page_id       =  po.page_id
             and  bms.parent_nr     =  po.post_nr
             -- Skip deleted, but do _load_hidden posts.
             and  po.deleted_status = ${DeletedStatus.NotDeleted.toInt}
             and  (po.type is null  or  po.type between 1 and 12)  -- [depr_post_type]
          $groupBy__siteId_postId
          -- This is for the complete result set (after union).
          order by created_at desc
          """
    val bookmarks = MutArrBuf[Post]()
    val bookmarkedPosts = MutArrBuf[Post]()
    runQueryAndForEachRow(query, values, rs => {
      val p = readPost(rs)
      if (p.tyype == PostType.Bookmark) bookmarks.append(p)
      else bookmarkedPosts.append(p)
    })
    (bookmarks.to(Vec), bookmarkedPosts.to(Vec))
  }


  /*
  def loadPosts(authorId: UserId, includeTitles: Boolean, includeChatMessages: Boolean,
        limit: Int, orderBy: OrderBy, onPageId: Option[PageId], onlyUnapproved: Boolean)
        : immutable.Seq[Post] = {

    val values = ArrayBuffer(siteId, authorId.asAnyRef)

    val andSkipTitles = includeTitles ? "" | s"and post_nr <> $TitleNr"
    val andSkipChat = includeChatMessages ?
          ? Skip bookmarks too ?
      "" | s"and (po.type is null or po.type <> ${PostType.ChatMessage.toInt})"
    val andOnlyUnapproved = onlyUnapproved ? "po.curr_rev_nr > approved_rev_nr" | ""

    val andOnCertainPage = onPageId map { pageId =>
      values.append(pageId)
      s"and po.page_id = ?"
    } getOrElse ""

    val query = i"""
      -- pat_node_rels_t [AuthorOf]
      $select__posts_po__leftJoin__patPostRels_pa
      where po.site_id = ? and po.created_by_id = ? $andSkipTitles
          $andSkipChat $andOnCertainPage $andOnlyUnapproved
      $groupBy__siteId_postId
      order by po.created_at ${descOrAsc(orderBy)} limit ?
      """
    runQueryFindMany(query, List(siteId, authorId.asAnyRef, limit.asAnyRef), rs => {
      readPost(rs)
    })
  } */


  def loadPostsByQuery(postQuery: PostQuery): immutable.Seq[Post] = {
    dieIf(postQuery.orderBy != OrderBy.MostRecentFirst, "EdE1DRJ7Y", "Unimpl")

    val values = ArrayBuffer[AnyRef](siteId.asAnyRef)

    val (andAuthorEq, andTypeEq) = postQuery match {
      case q: PostQuery.PostsByAuthor =>
        // Including the author's anon posts, hasn't been implemented.
        unimplIf(postQuery.inclAnonPosts, "TyEANONUNIMP04") ; ANON_UNIMPL
        values.append(q.authorId.asAnyRef)

        val andTypeEq = q.onlyPostType map { t =>
          // This not in use  [onlyPostType_not_used]. I thought this'd be for loading
          // bookmarks, but they're loaded via  loadBookmarksAndBookmarkedPosts()  instead.
          assert(false)
          values.append(t.toInt.asAnyRef)
          "and po.post_type = ?"
        } getOrElse ""

        ("and po.created_by_id = ?", andTypeEq)
      case _ =>
        ("", "")
    }

    val andNotTitle = postQuery.inclTitles ? "" | s"and po.post_nr <> $TitleNr"

    val andSomeVersionApproved = postQuery.inclUnapproved ?
          "" | and__po_approved_at__is_not_null

    // This'll require a join w pages3 and categories3.
    val andPageNotUnlisted_unimpl = !postQuery.inclUnlistedPagePosts ? "" | ""

    val (andRelatedPatIdEq, andRelTypeEq, anyOrderByRelAddedAt)  = postQuery match {
      case q: PostQuery.PostsRelatedToPat[_] =>
        CLEAN_UP; REMOVE // Or? Not in use.
        // Including tasks assigned to one's anonyms, hasn't been implemented.
        unimplIf(postQuery.inclAnonPosts, "TyEANONUNIMP05") ; ANON_UNIMPL
        // Currently using  tx.loadPatPostRels()  instead, see [load_posts_by_rels],
        // and this match-case branch is dead code.
        unimpl("Would .not_load_all_post_rels [TyE602MRTL5]")
        /*
        values.append(q.relatedPatId.asAnyRef)
        values.append(q.relType.toInt.asAnyRef)
        ("and pa.from_pat_id_c = ?"
        , "and pa.rel_type_c = ?"
        , Some(
            // Sort by most recent (max & desc) relevant relationship  (and unique_post_id,
            // just to always get the same order, also if same timestamp).
            // (Need sth like max() or min() because we array_agg rows from pa =
            // pat_node_rels_t.)
            "order by max(pa.created_at) desc, po.unique_post_id desc")) // [.same_time]
            */
      case _ => ("", "", None)
    }

    val select__posts_po__theJoin__patPostRels_pa =
          if (andRelatedPatIdEq.nonEmpty) {
            CLEAN_UP; REMOVE // Or? Not in use.
            unimpl("Would .not_load_all_post_rels [TyE602MRTL6]")

            // Then we want only posts with at least one relationship to the pat id.
            // However, there might be *other* relationships, from other pats to
            // the posts. And we want those as well, so we'll get all authors and
            // all assigned-to etc. — We need to first find the relevant
            // `andRelatedPatIdEq` relationships, and then, for each post in those
            // relationships, look up that post and load *all* its relationships.
            //
            // So, first look at pat_node_rels_t (currently named post_actions3)
            // and then do an inner join with posts_t (posts3).
            /*  Too complicated!?  Instead,
                  1: look up rels, 2: filter posts, 3: get posts by id  ?
            s"""
            with p0 as (
                select site_id, unique_post_id,
                from posts3 po
                inner join post_actions3 pa
                    on  pa.site_id = po.site_id
                    and pa.to_post_id_c = po.unique_post_id
                    $and__pa_relType__in__aut_own_asg  ?
                where
                    pa.site_id = ? and
                    pa.from_pat_id_c = ? and
                       ** oops would need to update dormant_status_c **
                    (pa.dormant_status_c is null or pa.dormant_status_c = 0) and
                    po.closed_status = 0 and
                    po.deleted_status = 0 and   ... ? was there sth more ?
                )
            ${select__posts_po__innerJoin__patPostRels_pa(postsTableName = "p0")}  """*/

            // BU G
            // This works but finds only relationships from  postQuery.relatedPatId,
            // so we won't see e.g. all assignees, only pat `relatedPatId`.
            select__posts_po__innerJoin__patPostRels_pa
          }
          else {
            // Now we want posts also if there is no pat-post relationship.
            // So, a left outer join.
            select__posts_po__leftJoin__patPostRels_pa
          }

    val andOnlyPublic =  // [all_0_incl_bookmarks]
          if (false) ""
          else s"and po.post_nr >= ${PageParts.MinPublicNr}"

    val sqlQuery = s""" -- loadPostsByQuery
          $select__posts_po__theJoin__patPostRels_pa
          where po.site_id = ?
              $andAuthorEq  -- pat_node_rels_t [AuthorOf]  needs new sub query?
              $andOnlyPublic
              $andTypeEq
              $andNotTitle
              $andSomeVersionApproved
              $andPageNotUnlisted_unimpl
              $andRelatedPatIdEq
              $andRelTypeEq
          $groupBy__siteId_postId ${
          anyOrderByRelAddedAt getOrElse s"""
          order by po.created_at desc,
             -- Page title and body have the same creation time.  [.same_time]
             -- Consider the title created before the page body.
             po.page_id desc, po.post_nr desc
             """ }
          limit ${postQuery.limit} """

    runQueryFindMany(sqlQuery, values.toList, rs => {
      readPost(rs)
    })
  }


  def loadPostsByTag(tagTypeId: TagTypeId, inclUnapproved: Bo, limit: i32,
          orderBy: OrderBy): immutable.Seq[Post] = {
    dieIf(orderBy != OrderBy.MostRecentFirst, "TyE60RKTJF9", "Unimpl")
    COULD_OPTIMIZE // inner-join posts3 and tags_t first?  [posts_join_order]

    val andSomeVersionApproved = inclUnapproved ?
          "" | and__po_approved_at__is_not_null

    val orderBySql = orderBy match {
      case OrderBy.MostRecentFirst => "po.created_at desc"
      case OrderBy.OldestFirst => "po.created_at asc"
    }
    /* [sort_tag_vals_in_pg]
    val orderBySql = orderBy match {
      case PostsWithTagOrder.ByPublishedAt(desc) => "po.created_at desc, po.unique_post_id desc"
      case PostsWithTagOrder.ByTagValue(desc, valType) =>
        val colName = valType match {
          case "i32" => "val_i32_c"
          case "f64" => "val_f64_c"
          case "str" => "val_str_c"
        }
        val descSql = if (desc) " desc" else ""
        s"t.$colName $descSql"  // maybe better avoid:  po.created_at desc, po.unique_post_id desc  ?
    } */

    val query = s""" -- loadPostsByTag
          $select__posts_po__leftJoin__patPostRels_pa
          inner join  tags_t t
             on   t.site_id_c    =  po.site_id
             and  t.on_post_id_c =  po.unique_post_id
             and  t.tagtype_id_c =  ?
          where  po.site_id =  ?
            and  po.post_nr >= ${PageParts.MinPublicNr} -- [all_0_incl_bookmarks]
            and  (po.type is null  or  po.type between 1 and 12)  -- [depr_post_type]
            and  po.deleted_status = ${DeletedStatus.NotDeleted.toInt}
            and  po.hidden_at is null
            $andSomeVersionApproved
          $groupBy__siteId_postId
          order by  $orderBySql  limit $limit """
    runQueryFindMany(query, List(tagTypeId.asAnyRef, siteId.asAnyRef), rs => {
      readPost(rs)
    })
  }


  RENAME // to loadEmbeddedCommentsActiveOnly? or add an activeOnly: Bo param
  def loadEmbeddedCommentsApprovedNotDeleted(limit: Int, orderBy: OrderBy): immutable.Seq[Post] = {
    dieIf(orderBy != OrderBy.MostRecentFirst, "TyE60RKTJF4", "Unimpl")
    COULD_OPTIMIZE // It would be better to inner-join posts3 and pages3 first, before
    // left-outer-joining with post_actions3? [posts_join_order]
    val query = s""" -- loadEmbeddedCommentsApprovedNotDeleted
      $select__posts_po__leftJoin__patPostRels_pa
      inner join pages3 pg using (site_id, page_id)
      where pg.site_id = ?
        and pg.page_role = ${PageType.EmbeddedComments.toInt}
        and po.post_nr <> $TitleNr
        and po.post_nr <> $BodyNr
        and (po.type is null or po.type = ${PostType.Normal.toInt})
        and po.deleted_status = ${DeletedStatus.NotDeleted.toInt}
        and po.hidden_at is null
        and po.approved_at is not null
      $groupBy__siteId_postId
      order by  po.created_at desc, po.unique_post_id desc  limit $limit
      """
    runQueryFindMany(query, List(siteId.asAnyRef), rs => {
      readPost(rs)
    })
  }


  /** Doesn't join with & aggregate pat_node_rels_t.
   */
  def loadPostsByTimeExclAggs(timeRange: TimeRange, toIndex: Bo, orderBy: OrderBy, limit: i32)
          : ImmSeq[Post] = {
    dieIf(orderBy != OrderBy.MostRecentFirst, "TyE60RKTGF9", "Unimpl")
    dieIf(!timeRange.toIsIncl, "TyE60RKTGFA", "Unimpl") // we use '<=' below
    dieIf(!toIndex, "TyE60RKTGFB", "Unimpl") // there're _posts_to_index clauses below

    // Currently [all_time_ranges_start_at_time_0], so we can ignore the lower bound.
    val query = s""" -- loadPostsByTimeExclAggs,  uses ix: posts_i_createdat_id
          select * from  posts3
          where  site_id = ?
              and (
                  created_at < ?
                  or (created_at = ? and unique_post_id <= ?))
              -- Only _posts_to_index:
              and ${SearchSiteDaoMixin.PostShouldBeIndexedTests}
          order by  created_at desc,  unique_post_id desc
          limit  $limit  """

    val values = List(
          siteId.asAnyRef,
          timeRange.to.toJavaDate,
          timeRange.to.toJavaDate,
          timeRange.toOfs.asAnyRef)

    runQueryFindMany(query, values, rs => {
      readPost(rs, inclAggs = false)
    })
  }


  /** Doesn't join with & aggregate pat_node_rels_t.
    */
  def loadPopularPostsByPageExclAggs(pageIds: Iterable[PageId], limitPerPage: Int,
          exclOrigPost: Bo): Map[PageId, immutable.Seq[Post]] = {
    if (pageIds.isEmpty)
      return Map.empty

    // Finds the `limitPerPage` most like-voted replies on each page.
    val query = s""" -- loadPopularPostsByPageExclAggs
      select * from (
        -- select__posts_po__leftJoin__patPostRels_pa  — no, we're excluding relationships.
        select
          row_number() over (partition by page_id order by num_like_votes desc) as rownum,
          p.*
        from posts3 p
        where p.site_id = ?
          and p.page_id in (${makeInListFor(pageIds)})
          and p.post_nr >= ${exclOrigPost ? PageParts.FirstReplyNr | PageParts.BodyNr}
          -- We want public posts only. The line above is enough, but let's include
          -- this check too, for clarity:
          and p.post_nr >= ${PageParts.MinPublicNr}
          -- Bookmarks have nrs < 0, but let's include here anyway.
          and (p.type is null or p.type not in (
                ${PostType.Bookmark.toInt}, ${PostType.CompletedForm.toInt}))
          and length(p.approved_html_sanitized) > 20
          and p.collapsed_status = 0
          and p.closed_status = 0
          and p.hidden_at is null
          and p.deleted_status = 0
          and p.num_pending_flags = 0
          and p.num_unwanted_votes = 0
        ) by_page
      where by_page.rownum <= $limitPerPage
      """
    val values = siteId.asAnyRef :: pageIds.toList

    runQueryBuildMultiMap(query, values, rs => {
      val post = readPost(rs, inclAggs = false)
      (post.pageId, post)
    })
  }



  def loadApprovedOrigPostAndRepliesByPage(pageIds: Iterable[PageId]): Map[PageId, immutable.Seq[Post]] = {
    if (pageIds.isEmpty)
      return Map.empty

    val query = s""" -- loadApprovedOrigPostAndRepliesByPage
      $select__posts_po__leftJoin__patPostRels_pa
        where po.site_id = ?
          and po.page_id in (${makeInListFor(pageIds)})
          and po.post_nr <> ${PageParts.TitleNr}
          -- This excludes bookmarks and private comments.
          and po.post_nr >= ${PageParts.MinPublicNr}
          and po.approved_at is not null
          and (po.type is null or po.type not in (
            ${PostType.BottomComment.toInt},  -- [2GYKFS4]
            ${PostType.MetaMessage.toInt},
            ${PostType.ChatMessage.toInt},
            -- Don't need to list these (bookmarks & forms), but why not:
            -- (Hmm? But do need to list forms? They have nrs > 0?)
            ${PostType.Bookmark.toInt},
            ${PostType.CompletedForm.toInt}
            ))
          and po.closed_status = 0
          and po.hidden_at is null
          and po.deleted_status = 0
          and po.parent_nr = ${PageParts.BodyNr}
      $groupBy__siteId_postId
      """
    val values = siteId.asAnyRef :: pageIds.toList

    runQueryBuildMultiMap(query, values, rs => {
      val post = readPost(rs)
      (post.pageId, post)
    })
  }



  def loadPostsToReview(): immutable.Seq[Post] = {
    val flaggedPosts = loadPostsToReviewImpl("""
      po.deleted_status = 0 and
      po.num_pending_flags > 0
      """)
    val unapprovedPosts = loadPostsToReviewImpl("""
      po.deleted_status = 0 and
      po.num_pending_flags = 0 and
      (po.approved_rev_nr is null or po.approved_rev_nr < po.curr_rev_nr)
      """)
    val postsWithSuggestions = loadPostsToReviewImpl("""
      po.deleted_status = 0 and
      po.num_pending_flags = 0 and
      po.approved_rev_nr = curr_rev_nr and
      po.num_edit_suggestions > 0
      """)
    (flaggedPosts ++ unapprovedPosts ++ postsWithSuggestions).to(immutable.Seq)
  }


  private def loadPostsToReviewImpl(whereTests: String): ArrayBuffer[Post] = {
    val query = s""" -- loadPostsToReviewImpl
          $select__posts_po__leftJoin__patPostRels_pa
          where po.site_id = ? and $whereTests
          $groupBy__siteId_postId
          """
    val values = List(siteId.asAnyRef)
    var results = ArrayBuffer[Post]()
    runQuery(query, values, rs => {
      while (rs.next()) {
        val post = readPost(rs)
        results += post
      }
    })
    results
  }


  override def nextPostId(): PostId = {
    val query = """ -- nextPostId
      select max(unique_post_id) max_id from posts3 where site_id = ?
      """
    runQuery(query, List(siteId.asAnyRef), rs => {
      rs.next()
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      maxId + 1
    })
  }


  override def insertPost(post: Post): Unit = {
    dieIf(isProd && post.tyype == PostType.Bookmark,
          "TyEBOOKM0ENA11", "Bookmarks not yet enabled")

    val statement = """ -- insertPost
      insert into posts3(
        site_id,
        unique_post_id,
        ext_id,
        page_id,
        post_nr,
        parent_nr,
        multireply,
        type,

        created_at,
        created_by_id,

        curr_rev_started_at,
        curr_rev_by_id,
        curr_rev_last_edited_at,
        curr_rev_source_patch,
        curr_rev_nr,

        last_approved_edit_at,
        last_approved_edit_by_id,
        num_distinct_editors,

        safe_rev_nr,
        approved_source,
        approved_html_sanitized,
        approved_at,
        approved_by_id,
        approved_rev_nr,

        collapsed_status,
        collapsed_at,
        collapsed_by_id,

        closed_status,
        closed_at,
        closed_by_id,

        hidden_at,
        hidden_by_id,
        hidden_reason,

        deleted_status,
        deleted_at,
        deleted_by_id,

        pinned_position,
        branch_sideways,

        num_pending_flags,
        num_handled_flags,
        num_edit_suggestions,

        num_like_votes,
        num_wrong_votes,
        num_bury_votes,
        num_unwanted_votes,
        num_times_read,

        smtp_msg_id_prefix_c
        )
      values (
        ?, ?, ?, ?, ?, ?, ?, ?,
        ?, ?,
        ?, ?, ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?, ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?, ?,
        ?, ?, ?,
        ?, ?, ?, ?, ?,
        ?)"""

    val values = List[AnyRef](
      siteId.asAnyRef, post.id.asAnyRef, post.extImpId.orNullVarchar, post.pageId, post.nr.asAnyRef,
      post.parentNr.orNullInt, toDbMultireply(post.multireplyPostNrs),
      (post.tyype != PostType.Normal) ? post.tyype.toInt.asAnyRef | NullInt,

      d2ts(post.createdAt),
      post.createdById.asAnyRef,

      post.currentRevStaredAt,
      post.currentRevisionById.asAnyRef,
      post.currentRevLastEditedAt.orNullTimestamp,
      post.currentRevSourcePatch.orNullVarchar,
      post.currentRevisionNr.asAnyRef,

      o2ts(post.lastApprovedEditAt),
      post.lastApprovedEditById.orNullInt,
      post.numDistinctEditors.asAnyRef,

      post.safeRevisionNr.orNullInt,
      post.approvedSource.orNullVarchar,
      post.approvedHtmlSanitized.orNullVarchar,
      o2ts(post.approvedAt),
      post.approvedById.orNullInt,
      post.approvedRevisionNr.orNullInt,

      post.collapsedStatus.underlying.asAnyRef,
      o2ts(post.collapsedAt),
      post.collapsedById.orNullInt,

      post.closedStatus.underlying.asAnyRef,
      o2ts(post.closedAt),
      post.closedById.orNullInt,

      o2ts(post.bodyHiddenAt),
      post.bodyHiddenById.orNullInt,
      post.bodyHiddenReason.orNullVarchar,

      post.deletedStatus.underlying.asAnyRef,
      o2ts(post.deletedAt),
      post.deletedById.orNullInt,

      post.pinnedPosition.orNullInt,
      post.branchSideways.orNullInt,

      post.numPendingFlags.asAnyRef,
      post.numHandledFlags.asAnyRef,
      post.numPendingEditSuggestions.asAnyRef,

      post.numLikeVotes.asAnyRef,
      post.numWrongVotes.asAnyRef,
      post.numBuryVotes.asAnyRef,
      post.numUnwantedVotes.asAnyRef,
      post.numTimesRead.asAnyRef,

      post.smtpMsgIdPrefix.orNullVarchar,
      )

    runUpdate(statement, values)
  }


  def updatePost(post: Post): Unit = {
    dieIf(isProd && post.tyype == PostType.Bookmark,
          "TyEBOOKM0ENA12", "Bookmarks not yet enabled")

    val statement = """ -- updatePost
      update posts3 set
        page_id = ?,
        post_nr = ?,
        parent_nr = ?,
        multireply = ?,
        type = ?,

        curr_rev_started_at = ?,
        curr_rev_by_id = ?,
        curr_rev_last_edited_at = ?,
        curr_rev_source_patch = ?,
        curr_rev_nr = ?,
        prev_rev_nr = ?,

        last_approved_edit_at = ?,
        last_approved_edit_by_id = ?,
        num_distinct_editors = ?,

        safe_rev_nr = ?,
        approved_source = ?,
        approved_html_sanitized = ?,
        approved_at = ?,
        approved_by_id = ?,
        approved_rev_nr = ?,

        collapsed_status = ?,
        collapsed_at = ?,
        collapsed_by_id = ?,

        closed_status = ?,
        closed_at = ?,
        closed_by_id = ?,

        hidden_at = ?,
        hidden_by_id = ?,
        hidden_reason = ?,

        deleted_status = ?,
        deleted_at = ?,
        deleted_by_id = ?,

        pinned_position = ?,
        branch_sideways = ?,

        num_pending_flags = ?,
        num_handled_flags = ?,
        num_edit_suggestions = ?,

        num_like_votes = ?,
        num_wrong_votes = ?,
        num_bury_votes = ?,
        num_unwanted_votes = ?,
        num_times_read = ?,

        smtp_msg_id_prefix_c = ?

      where site_id = ? and unique_post_id = ?"""

    val values = List[AnyRef](
      post.pageId,
      post.nr.asAnyRef,
      post.parentNr.orNullInt,
      toDbMultireply(post.multireplyPostNrs),
      (post.tyype != PostType.Normal) ? post.tyype.toInt.asAnyRef | NullInt,

      post.currentRevStaredAt,
      post.currentRevisionById.asAnyRef,
      post.currentRevLastEditedAt.orNullTimestamp,
      post.currentRevSourcePatch.orNullVarchar,
      post.currentRevisionNr.asAnyRef,
      post.previousRevisionNr.orNullInt,

      post.lastApprovedEditAt.orNullTimestamp,
      post.lastApprovedEditById.orNullInt,
      post.numDistinctEditors.asAnyRef,

      post.safeRevisionNr.orNullInt,
      post.approvedSource.orNullVarchar,
      post.approvedHtmlSanitized.orNullVarchar,
      o2ts(post.approvedAt),
      post.approvedById.orNullInt,
      post.approvedRevisionNr.orNullInt,

      post.collapsedStatus.underlying.asAnyRef,
      o2ts(post.collapsedAt),
      post.collapsedById.orNullInt,

      post.closedStatus.underlying.asAnyRef,
      o2ts(post.closedAt),
      post.closedById.orNullInt,

      o2ts(post.bodyHiddenAt),
      post.bodyHiddenById.orNullInt,
      post.bodyHiddenReason.orNullVarchar,

      post.deletedStatus.underlying.asAnyRef,
      o2ts(post.deletedAt),
      post.deletedById.orNullInt,

      post.pinnedPosition.orNullInt,
      post.branchSideways.orNullInt,

      post.numPendingFlags.asAnyRef,
      post.numHandledFlags.asAnyRef,
      post.numPendingEditSuggestions.asAnyRef,

      post.numLikeVotes.asAnyRef,
      post.numWrongVotes.asAnyRef,
      post.numBuryVotes.asAnyRef,
      post.numUnwantedVotes.asAnyRef,
      post.numTimesRead.asAnyRef,

      post.smtpMsgIdPrefix.orNullVarchar,

      siteId.asAnyRef, post.id.asAnyRef)

    runUpdate(statement, values)
  }


  private def readPost(rs: js.ResultSet, pageId: Option[PageId] = None,
          inclAggs: Bo = true): Post = {

    // (Simpler to debug, if can see this directly.)
    val postId = rs.getInt("UNIQUE_POST_ID")

    // An array of [PatPostRel.Type, from-pat-id].
    val anyVecOfPatRelVecs: Opt[Vec[Vec[Int]]] =
          if (!inclAggs) None
          else getOptArrayOfArrayOfInt32(rs, "pat_post_rels_array")

    val (ownerIds: Vec[Int], authorIds: Vec[Int], assignedToIds: Vec[Int]) =
            anyVecOfPatRelVecs match {
      case None =>
         (Vec.empty, Vec.empty, Vec.empty)
      case Some(vecOfPatRelVecs: Vec[Vec[Int]]) =>
        val ownerIds = MutHashSet[Int]()  // why won't PatId work, it's just type = i32 = Int
        val authorIds = MutHashSet[Int]()
        val assignedToIds = MutHashSet[Int]()
        for (patRelVec: Vec[Int] <- vecOfPatRelVecs) {
          dieIf(patRelVec.size != 2, "TyE50MTEAKR2", s"Pat rel vec len: ${patRelVec.size}")
          val relTypeInt = patRelVec(0)
          val patId = patRelVec(1)
          // Or, hmm, move to object PatNodeRelType?
          relTypeInt match {
            case PatNodeRelType.OwnerOf_later.IntVal =>
              ownerIds.add(patId)
            case PatNodeRelType.AuthorOf_later.IntVal =>
              authorIds.add(patId)
            case PatNodeRelType.AssignedTo.IntVal =>
              assignedToIds.add(patId)
            case x =>
              // We load only those in: $and__pa_relType__in__aut_own_asg.
              die("TyE7MWJC21", s"Unexpected pat-to-node rel_type_c: $x")
          }
        }
        (ownerIds.to(Vec), authorIds.to(Vec), assignedToIds.to(Vec))
    }

    Post(
      id = postId,
      extImpId = getOptString(rs, "ext_id"),
      pageId = pageId.getOrElse(rs.getString("PAGE_ID")),
      nr = rs.getInt("post_nr"),
      parentNr = getOptionalInt(rs, "parent_nr"),
      multireplyPostNrs = fromDbMultireply(rs.getString("MULTIREPLY")),
      tyype = PostType.fromInt(rs.getInt("TYPE")).getOrElse(PostType.Normal),
      createdAt = getDate(rs, "CREATED_AT"),
      createdById = rs.getInt("CREATED_BY_ID"),
      ownerIds = ownerIds,
      authorIds = authorIds,
      assigneeIds = assignedToIds,
      currentRevStaredAt = getDate(rs, "curr_rev_started_at"),
      currentRevisionById = rs.getInt("curr_rev_by_id"),
      currentRevLastEditedAt = getOptionalDate(rs, "curr_rev_last_edited_at"),
      currentRevSourcePatch = Option(rs.getString("curr_rev_source_patch")),
      currentRevisionNr = rs.getInt("curr_rev_nr"),
      previousRevisionNr = getOptInt(rs, "prev_rev_nr"),
      lastApprovedEditAt = getOptionalDate(rs, "LAST_APPROVED_EDIT_AT"),
      lastApprovedEditById = getOptInt(rs, "LAST_APPROVED_EDIT_BY_ID"),
      numDistinctEditors = rs.getInt("NUM_DISTINCT_EDITORS"),
      safeRevisionNr = getOptInt(rs, "safe_rev_nr"),
      approvedSource = Option(rs.getString("APPROVED_SOURCE")),
      approvedHtmlSanitized = Option(rs.getString("APPROVED_HTML_SANITIZED")),
      approvedAt = getOptionalDate(rs, "APPROVED_AT"),
      approvedById = getOptInt(rs, "APPROVED_BY_ID"),
      approvedRevisionNr = getOptInt(rs, "approved_rev_nr"),
      collapsedStatus = new CollapsedStatus(rs.getInt("COLLAPSED_STATUS")),
      collapsedAt = getOptionalDate(rs, "COLLAPSED_AT"),
      collapsedById = getOptInt(rs, "COLLAPSED_BY_ID"),
      closedStatus = new ClosedStatus(rs.getInt("CLOSED_STATUS")),
      closedAt = getOptionalDate(rs, "CLOSED_AT"),
      closedById = getOptInt(rs, "CLOSED_BY_ID"),
      bodyHiddenAt = getOptionalDate(rs, "HIDDEN_AT"),
      bodyHiddenById = getOptInt(rs, "HIDDEN_BY_ID"),
      bodyHiddenReason = getOptStringEmptyAsNone(rs, "hidden_reason"),
      deletedStatus = new DeletedStatus(rs.getInt("DELETED_STATUS")),
      deletedAt = getOptionalDate(rs, "DELETED_AT"),
      deletedById = getOptionalInt(rs, "DELETED_BY_ID"),
      pinnedPosition = getOptionalInt(rs, "PINNED_POSITION"),
      branchSideways = getOptionalByte(rs, "branch_sideways"),
      numPendingFlags = rs.getInt("NUM_PENDING_FLAGS"),
      numHandledFlags = rs.getInt("NUM_HANDLED_FLAGS"),
      numPendingEditSuggestions = rs.getInt("NUM_EDIT_SUGGESTIONS"),
      numLikeVotes = rs.getInt("NUM_LIKE_VOTES"),
      numWrongVotes = rs.getInt("NUM_WRONG_VOTES"),
      numBuryVotes = rs.getInt("NUM_BURY_VOTES"),
      numUnwantedVotes = rs.getInt("NUM_UNWANTED_VOTES"),
      numTimesRead = rs.getInt("NUM_TIMES_READ"),
      smtpMsgIdPrefix = getOptString(rs, "smtp_msg_id_prefix_c"),
      )
  }


  // Will return a set or list, later when there can be many authors per post.
  def loadAuthorIdsByPostId(postIds: Set[PostId]): Map[PostId, UserId] = {
    // Tested here:
    //   - api-search-full-text.1br.f  TyT70ADNEFTD36.TyT5086XJW2
    if (postIds.isEmpty)
      return Map.empty

    val query = s""" -- loadAuthorIdsByPostId
      select unique_post_id, created_by_id
      from posts3    -- + pat_node_rels_t [AuthorOf]
      -- select__posts_po__leftJoin__patPostRels_pa
      where site_id = ?
        and unique_post_id in(${makeInListFor(postIds)}) """

    val values = ArrayBuffer(siteId)
    values.append(postIds.toSeq: _*)

    runQueryBuildMap(query, values.toList.map(_.asAnyRef), rs => {
      val postId = rs.getInt("unique_post_id")
      val authorId = rs.getInt("created_by_id")
      postId -> authorId
    })
  }


  def deleteVote(pageId: PageId, postNr: PostNr, voteType: PostVoteType, voterId: UserId)
        : Opt[PatIds] = {
    REFACTOR // Break out fn, merge w deletePatNodeRels() below?
    val statement = """ -- deleteVote
          delete from post_actions3
          where site_id = ? and page_id = ? and post_nr = ? and rel_type_c = ?
              and (from_pat_id_c = ?   or  from_true_id_c = ?)
          returning  from_pat_id_c,  from_true_id_c """

    val values = List[AnyRef](siteId.asAnyRef, pageId, postNr.asAnyRef,
          toActionTypeInt(voteType), voterId.asAnyRef, voterId.asAnyRef)

    var res: Opt[PatIds] = None
    runUpdateQuery(statement, values, rs => {
      devDieIf(res.isDefined, "TyE502MFKJ", s"s$siteId: Found > 1 votes, page ${
            pageId} post ${postNr}, vote type $voteType, voterId $voterId")
      res = Some(getPatIds(rs, "from_pat_id_c", "from_true_id_c"))
    })

    res
  }


  def loadVoterIds(postId: PostId, voteType: PostVoteType): Seq[UserId] = {
    // Tests:
    //  - alias-anons-basic.2br.f.e2e.ts  TyTLISTVOTES

    // Sort [newest_vote_first] — this let's one see who just voted, if one got
    // a notification about a new Like vote. Or who just flagged the post, etc.
    // (Order by from_pat_id_c too, so tests will be more stable.)
    val query = """ -- loadVoterIds
          select distinct from_pat_id_c, max(created_at)
          from post_actions3
          where site_id = ? and to_post_id_c = ? and rel_type_c = ?
          group by from_pat_id_c
          order by max(created_at) desc, from_pat_id_c
          limit 500 """  // for now — just to have *some* limit [1WVKPW02]

    val values = List[AnyRef](siteId.asAnyRef, postId.asAnyRef, voteType.toInt.asAnyRef)
    runQueryFindMany(query, values, rs => {
      rs.getInt("from_pat_id_c")
    })
  }


  def loadActionsOnPage(pageId: PageId): immutable.Seq[PostAction] = {
    loadActionsOnPageImpl(Some(pageId), userId = None,
          relTypes = Vec(), limit = Some(19001))
  }


  def loadActionsByUserOnPage(reqrId: PatId, userId: UserId, pageId: PageId)
        : immutable.Seq[PostAction] = {
    unimplIf(reqrId != userId, "Viewing sbd else's votes on a page [TyE503RNGC]")
    // One can see one's own anons and pseudonyms.
    val inclAnons = reqrId == userId
    loadActionsOnPageImpl(Some(pageId), userId = Some(userId), inclAnons = inclAnons,
          relTypes = Vec(), limit = Some(19001)) // hmm
  }


  def loadPatPostRels[T <: PatNodeRelType](forPatId: PatId, relType: T,
                                           onlyOpenPosts: Bo, limit: i32)
        : immutable.Seq[PatNodeRel[T]] = {
    loadActionsOnPageImpl(pageId = None, userId = Some(forPatId), relTypes = Vec(relType),
          onlyOpenPosts = onlyOpenPosts, limit = Some(limit)).map(_.asInstanceOf[PatNodeRel[T]])
  }


  def loadAllPostActionsForExport(): immutable.Seq[PostAction] = {
    loadActionsOnPageImpl(pageId = None, userId = None,
          relTypes = Nil, limit = None)  // loads all
  }


  // Later: Make generic: [T <: PatNodeRelType] and return a PatNodeRel[T]
  private def loadActionsOnPageImpl(pageId: Option[PageId], userId: Option[UserId],
        relTypes: ImmSeq[PostActionType], limit: Opt[i32], onlyOpenPosts: Bo = false,
        inclAnons: Bo = false)
        : immutable.Seq[PostAction] = {
    val values = ArrayBuffer[AnyRef](siteId.asAnyRef)

    val andPageIdEq = pageId match {
      case None => ""
      case Some(id) =>
        values.append(id)
        s"and pa.page_id = ?"
    }

    val andCreatedBy = userId match {
      case None => ""
      case Some(id) =>
        values.append(id.asAnyRef)
        if (!inclAnons) {
          "and  pa.from_pat_id_c = ?"
        }
        else {
          values.append(id.asAnyRef)
          "and  (pa.from_pat_id_c = ?  or  pa.from_true_id_c = ?)"
        }
    }

    val andRelTypeIn = {
      if (relTypes.isEmpty) ""
      else {
        values.appendAll(relTypes.map(_.toInt.asAnyRef))
        s"and pa.rel_type_c in (${makeInListFor(relTypes)})"
      }
    }

    val orderByAndLimit = {
      // Order by primary key, just to avoid [flappy_tests].
      var ordAndLim = "pa.to_post_id_c, pa.rel_type_c, pa.from_pat_id_c, pa.sub_type_c"
      // But if we want the N last pat-post-rels, then, we currently want the most recent
      // ones. So order by time, desc. (But keep the pk order too, to avoid different item
      // order in case of identical timestamps — which could make e2e tests sometimes fail.)
      limit foreach { lim =>
        ordAndLim = s"pa.created_at desc, $ordAndLim  limit $lim"
      }
      s"order by  $ordAndLim"
    }

    COULD // always incl deleted_status = 0, unless a specific pageId is specified? Hmm.
    COULD_OPTIMIZE // if "", includes deleted posts, are filtered out later?
    unimplIf(pageId.isDefined && onlyOpenPosts, o"""About to load only open posts on a
          specific page — but if page deleted, then what, ... nothing? [TyE5B2MJGPL0]""")
    val innerJoinOpenPosts: St = if (!onlyOpenPosts) "" else s"""
          inner join  posts3 po
             on  po.site_id = pa.site_id
            and  po.unique_post_id = pa.to_post_id_c
            and  po.closed_status = 0
            and  po.deleted_status = 0
            -- Later: Sometimes include [priv_comts]? (nr <= MaxPrivateNr)
            and  po.post_nr >= ${PageParts.MinPublicNr} -- [all_0_incl_bookmarks]
          inner join  pages3 pg
             on  pg.site_id = po.site_id
            and  pg.page_id = po.page_id
            and  pg.closed_at is null
            and  pg.deleted_at is null  """

    val query = s"""
          select  pa.to_post_id_c,
                  pa.page_id,
                  pa.post_nr,
                  pa.rel_type_c,
                  pa.sub_type_c,
                  pa.created_at,
                  pa.from_pat_id_c,
                  pa.from_true_id_c
          from  post_actions3 pa  $innerJoinOpenPosts
          where  pa.site_id = ?
                 $andPageIdEq
                 $andCreatedBy
                 $andRelTypeIn
                  -- This (post_actions3.post_nr) gets out of date if post moved to other page?
                  -- But the sign (> 0 or < 0) will at least stay the same. [priv_comts]
                  and  pa.post_nr >= ${PageParts.MinPublicNr} -- [all_0_incl_bookmarks]
          $orderByAndLimit  """

    val result = runQueryFindMany(query, values.toList, rs => {
      PostAction(
        uniqueId = rs.getInt("to_post_id_c"),
        pageId = rs.getString("page_id"),
        postNr = rs.getInt("post_nr"),
        doneAt = getWhen(rs, "created_at"),
        doerId = getPatIds(rs, "from_pat_id_c", "from_true_id_c"),
        actionType = fromActionTypeInt(rs.getInt("rel_type_c")))
    })

    result
  }


  def loadActionsDoneToPost(pageId: PageId, postNr: PostNr): immutable.Seq[PostAction] = {
    val query = """ -- loadActionsDoneToPost
      select distinct  to_post_id_c, rel_type_c, created_at, from_pat_id_c, from_true_id_c
      from  post_actions3
      where  site_id = ?
        and  page_id = ?
        and  post_nr = ?
      order by  to_post_id_c, rel_type_c, from_pat_id_c  -- to avoid [flappy_tests]
      """
    val values = List[AnyRef](siteId.asAnyRef, pageId, postNr.asAnyRef)
    runQueryFindMany(query, values, rs => {
      PostAction(
        uniqueId = rs.getInt("to_post_id_c"),
        pageId = pageId,
        postNr = postNr,
        doneAt = getWhen(rs, "created_at"),
        doerId = getPatIds(rs, "from_pat_id_c", "from_true_id_c"),
        actionType = fromActionTypeInt(rs.getInt("rel_type_c")))
    })
  }


  def loadFlagsFor(pagePostNrs: Iterable[PagePostNr]): immutable.Seq[PostFlag] = {
    if (pagePostNrs.isEmpty)
      return Nil

    val queryBuilder = new StringBuilder(256, s""" -- loadFlagsFor
      select to_post_id_c, page_id, post_nr, rel_type_c, created_at,
          from_pat_id_c, from_true_id_c
      from post_actions3
      where site_id = ?
        and rel_type_c in ($FlagValueSpam, $FlagValueInapt, $FlagValueOther)
        and (
      """)
    val values = ArrayBuffer[AnyRef](siteId.asAnyRef)
    var first = true
    pagePostNrs foreach { pagePostNr =>
      if (!first) {
        queryBuilder.append(" or ")
      }
      first = false
      queryBuilder.append("(page_id = ? and post_nr = ?)")
      values.append(pagePostNr.pageId, pagePostNr.postNr.asAnyRef)
    }
    val query = queryBuilder.append(
          // Avoid [flappy_tests]:
          ")  order by  to_post_id_c, rel_type_c, from_pat_id_c"
          ).toString
    runQueryFindMany(query, values.toList, rs => {
      val postAction = PostFlag(
        uniqueId = rs.getInt("to_post_id_c"),
        pageId = rs.getString("page_id"),
        postNr = rs.getInt("post_nr"),
        doneAt = getWhen(rs, "created_at"),
        flaggerId = getPatIds(rs, "from_pat_id_c", "from_true_id_c"),
        flagType = fromActionTypeIntToFlagType(rs.getInt("rel_type_c")))
      dieIf(!postAction.actionType.isInstanceOf[PostFlagType], "DwE2dpg4")
      postAction
    })
  }


  def clearFlags(pageId: PageId, postNr: PostNr, clearedById: UserId): Unit = {
    // Only soft-delete the flags. Might need later, for auditing purposes?
    val statement = s""" -- clearFlags
      update post_actions3
      set deleted_at = now_utc(), deleted_by_id = ?, updated_at = now_utc()
      where site_id = ? and
            page_id = ? and
            post_nr = ? and
            deleted_at is null and
            rel_type_c in ($FlagValueSpam, $FlagValueInapt, $FlagValueOther)
      """
    val values = List(clearedById.asAnyRef, siteId.asAnyRef, pageId, postNr.asAnyRef)
    runUpdate(statement, values)
  }


  def insertPostAction(postAction: PostAction): Unit = {
    postAction match {
      case vote: PostVote =>
        insertPostActionImpl(
              postId = vote.uniqueId, pageId = vote.pageId, postNr = vote.postNr,
              actionType = vote.voteType, doerId = vote.doerId, doneAt = vote.doneAt,
              manyOk = false)
      case flag: PostFlag =>
        insertPostActionImpl(
              postId = flag.uniqueId, pageId = flag.pageId, postNr = flag.postNr,
              actionType = flag.flagType, doerId = flag.doerId, doneAt = flag.doneAt,
              manyOk = true)
      case rel: PatNodeRel[_] =>
        // This covers owner-of  (or will owner-of be in pat_node_multi_rels_t?),
        // author-of and assigned-to.
        // (The other approach: PostVote and PostFlag, above, is deprecated.)
        insertPostActionImpl(
              postId = rel.uniqueId, pageId = rel.pageId, postNr = rel.postNr,
              actionType = rel.relType, doerId = rel.fromPatId, doneAt = rel.addedAt,
              manyOk = false)
    }
  }


  private def insertPostActionImpl(postId: PostId, pageId: PageId, postNr: PostNr,
        actionType: PostActionType, doerId: PatIds, doneAt: When, manyOk: Bo): Unit = {

    val subTypeOne: i32 = 1

    // Has the same person done this already (e.g. voted), using another persona?
    if (!manyOk) {
      // Let's run a `select`, so we'll know for sure what's wrong. If we instead
      // use `insert into ... where not exists (...)`, we can't know if 0 updated rows
      // is because of duplicated actions, or a SQL query or values bug.
      TESTS_MISSING // TyTALIVOTES
      val query = s"""
          select * from post_actions3
          where  site_id = ?
              and  to_post_id_c = ?
              and  rel_type_c = ?
              and  (from_pat_id_c = ?  or  from_true_id_c = ?)
              and  sub_type_c = $subTypeOne
              -- Let's skip, for now — otherwise might run into conflicts, if
              -- undoing the deletion of a vote?
              -- and  deleted_at is null
          limit 1 """
      val values = List(siteId.asAnyRef, postId.asAnyRef, toActionTypeInt(actionType),
            doerId.trueId.asAnyRef, doerId.trueId.asAnyRef)
      runQueryFindMany(query, values, rs => {
        throw DbDao.DuplicateVoteException
      })
    }

    // Upvoting a bookmark would be a bug.
    // Could search for all  insertPostAction()  usage too, throwForbidden  if wrong
    // post type?  [weird_post_actions]
    dieIf(postNr < PageParts.MinPublicNr, "TyEPRIVPOACT", o"""s$siteId: Trying to do sth
          with a private post, nr $postNr id $postId, action type: $actionType""")

    val statement = s"""
      insert into post_actions3(site_id, to_post_id_c, page_id, post_nr, rel_type_c,
          from_pat_id_c, from_true_id_c,
          created_at, sub_type_c)
      values (?, ?, ?, ?, ?, ?, ?, ?, $subTypeOne) """

    val values = List[AnyRef](siteId.asAnyRef, postId.asAnyRef, pageId, postNr.asAnyRef,
          toActionTypeInt(actionType), doerId.pubId.asAnyRef,
          doerId.anyTrueId.orNullInt32, doneAt.asTimestamp)
    val numInserted =
      try { runUpdate(statement, values) }
      catch {
        case ex: js.SQLException if isUniqueConstrViolation(ex) =>
          throw DbDao.DuplicateVoteException
      }
    dieIf(numInserted != 1, "DwE9FKw2", s"Error inserting action: numInserted = $numInserted")
  }


  def deletePatNodeRels(fromPatIds: Set[PatId], toPostId: PostId,
        relTypes: Set[PatNodeRelType]): i32 = {
    if (fromPatIds.isEmpty || relTypes.isEmpty) return 0
    val statement = s""" -- deletePatNodeRels
          delete from post_actions3
          where site_id = ? and
                to_post_id_c = ? and
                -- But not  from_true_id_c.  Let's say someone's pseudonym is assigned
                -- to a task, and the true user too (which sounds rare, but might
                -- happen). Then, if unassigning the true user, we wouldn't want the
                -- pseudonym to get unassigned too — that could reveal who the
                -- pseudonym is.  [deanon_risk]
                from_pat_id_c in (${makeInListFor(fromPatIds)}) and
                rel_type_c in (${makeInListFor(relTypes)}) """
    val values = ArrayBuffer[AnyRef](
          siteId.asAnyRef,
          toPostId.asAnyRef)
    values.appendAll(fromPatIds.map(_.asAnyRef))
    values.appendAll(relTypes.map(_.toInt.asAnyRef))
    runUpdate(statement, values.toList)
  }


  def loadLastPostRevision(postId: PostId): Option[PostRevision] =
    loadPostRevisionImpl(postId, PostRevision.LastRevisionMagicNr)


  def loadPostRevision(postId: PostId, revisionNr: Int): Option[PostRevision] =
    loadPostRevisionImpl(postId, revisionNr)


  private def loadPostRevisionImpl(postId: PostId, revisionNr: Int): Option[PostRevision] = {
    var query = s""" -- loadPostRevisionImpl
      select
        revision_nr, previous_nr, source_patch, full_source, title,
        composed_at, composed_by_id,
        approved_at, approved_by_id,
        hidden_at, hidden_by_id
      from post_revisions3
      where site_id = ? and post_id = ? and revision_nr = """
    var values = List(siteId.asAnyRef, postId.asAnyRef)

    if (revisionNr == PostRevision.LastRevisionMagicNr) {
      query += s"""(
        select max(revision_nr) from post_revisions3
        where site_id = ? and post_id = ?
        )"""
      values = values ::: List(siteId.asAnyRef, postId.asAnyRef)
    }
    else {
      query += "?"
      values = values ::: List(revisionNr.asAnyRef)
    }

    runQuery(query, values, rs => {
      if (!rs.next)
        return None

      Some(PostRevision(
        postId = postId,
        revisionNr = rs.getInt("revision_nr"),
        previousNr = getOptInt(rs, "previous_nr"),
        sourcePatch = Option(rs.getString("source_patch")),
        fullSource = Option(rs.getString("full_source")),
        title = Option(rs.getString("title")),
        composedAt = getDate(rs, "composed_at"),
        composedById = rs.getInt("composed_by_id"),
        approvedAt = getOptionalDate(rs, "approved_at"),
        approvedById = getOptInt(rs, "approved_by_id"),
        hiddenAt = getOptionalDate(rs, "hidden_at"),
        hiddenById = getOptInt(rs, "hidden_by_id")))
    })
  }


  def insertPostRevision(revision: PostRevision): Unit = {
    val statement = """ -- insertPostRevision
          insert into post_revisions3(
            site_id, post_id,
            revision_nr, previous_nr,
            source_patch, full_source, title,
        composed_at, composed_by_id,
            approved_at, approved_by_id,
            hidden_at, hidden_by_id)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """
    val values = List[AnyRef](
          siteId.asAnyRef, revision.postId.asAnyRef,
          revision.revisionNr.asAnyRef, revision.previousNr.orNullInt,
          revision.sourcePatch.orNullVarchar, revision.fullSource.orNullVarchar,
          revision.title.orNullVarchar,
      revision.composedAt, revision.composedById.asAnyRef,
          revision.approvedAt.orNullTimestamp, revision.approvedById.orNullInt,
          revision.hiddenAt.orNullTimestamp, revision.hiddenById.orNullInt)
    runUpdateExactlyOneRow(statement, values)
  }


  def updatePostRevision(revision: PostRevision): Unit = {
    UNTESTED
    val statement = """ -- updatePostRevision
      update post_revisions3 set
        source_patch = ?, full_source = ?, title = ?,
        composed_at = ?, combosed_by_id = ?,
        approved_at = ?, approved_by_id = ?,
        hidden_at = ?, hidden_by_id = ?
      where site_id = ? and post_id = ? and revision_nr = ?
      """
    val values = List[AnyRef](
      revision.sourcePatch.orNullVarchar, revision.fullSource.orNullVarchar,
      revision.title.orNullVarchar,
      revision.composedAt, revision.composedById.asAnyRef,
      revision.approvedAt.orNullTimestamp, revision.approvedById.orNullInt,
      revision.hiddenAt.orNullTimestamp, revision.hiddenById.orNullInt,
      siteId.asAnyRef, revision.postId.asAnyRef, revision.revisionNr.asAnyRef)
    runUpdateExactlyOneRow(statement, values)
  }

}


object PostsSiteDaoMixin {

  // dupl numbers [2PKWQUT0]
  private val VoteValueLike = 41
  private val VoteValueWrong = 42
  private val VoteValueBury = 43
  private val VoteValueUnwanted = 44
  private val FlagValueSpam = 51
  private val FlagValueInapt = 52
  private val FlagValueOther = 53


  def toActionTypeInt(actionType: PostActionType): AnyRef = (actionType match {
    case PatNodeRelType.OwnerOf_later => PatNodeRelType.OwnerOf_later.IntVal
    case PatNodeRelType.AuthorOf_later => PatNodeRelType.AuthorOf_later.IntVal
    case PatNodeRelType.AssignedTo => PatNodeRelType.AssignedTo.IntVal
    case PostVoteType.Like => VoteValueLike
    case PostVoteType.Wrong => VoteValueWrong
    case PostVoteType.Bury => VoteValueBury
    case PostVoteType.Unwanted => VoteValueUnwanted
    case PostFlagType.Spam => FlagValueSpam
    case PostFlagType.Inapt => FlagValueInapt
    case PostFlagType.Other => FlagValueOther
  }).asAnyRef


  REFACTOR // move to:  PatNodeRelType.fromInt32(Int): PatNodeRelType  [402KTHRNPQw]
  def fromActionTypeInt(value: Int, mab: MessAborter = IfBadDie): PostActionType =
    fromAnyActionTypeInt(value).getOrAbort(mab, "TyE0ACTTYPE", s"Not a post action type: $value")

  // Move this one too.
  def fromAnyActionTypeInt(value: Int): Opt[PostActionType] = Some(value match {
    case PatNodeRelType.OwnerOf_later.IntVal => PatNodeRelType.OwnerOf_later
    case PatNodeRelType.AuthorOf_later.IntVal => PatNodeRelType.AuthorOf_later
    case PatNodeRelType.AssignedTo.IntVal => PatNodeRelType.AssignedTo
    case VoteValueLike => PostVoteType.Like
    case VoteValueWrong => PostVoteType.Wrong
    case VoteValueBury => PostVoteType.Bury
    case VoteValueUnwanted => PostVoteType.Unwanted
    case FlagValueSpam => PostFlagType.Spam
    case FlagValueInapt => PostFlagType.Inapt
    case FlagValueOther => PostFlagType.Other
    case _ => return None
  })

  COULD // use MessAborter, if wrong type?
  def fromActionTypeIntToFlagType(value: Int): PostFlagType = {
    val tyype = fromActionTypeInt(value)
    require(tyype.isInstanceOf[PostFlagType], "DwE4GKP52")
    tyype.asInstanceOf[PostFlagType]
  }

  def postActionTypeIntToOptVoteType(value: i32): Opt[PostVoteType] = {
    val tyype = fromAnyActionTypeInt(value) getOrElse { return None }
    if (!tyype.isInstanceOf[PostVoteType]) return None
    Some(tyype.asInstanceOf[PostVoteType])
  }

}
