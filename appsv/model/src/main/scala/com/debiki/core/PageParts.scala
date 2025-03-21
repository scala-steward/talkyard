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

package com.debiki.core

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}
import Prelude._
import PageParts._
import org.scalactic.{Bad, Good, One, Or}


case class InterestingPosters(
  origPostAuthorId: Opt[PatId], // Might have been deleted
  lastReplyWhen: Opt[When], // REN to  lastApproved...  ?
  lastReplyById: Opt[PatId],
  frequentPosterIds: Seq[PatId]) {

  require(lastReplyWhen.isDefined == lastReplyById.isDefined, "TyE40WJKH21")
  require(origPostAuthorId isNot NoUserId, "TyE40WJKH22")
  require(lastReplyById isNot NoUserId, "TyE40WJKH23")
  require(!frequentPosterIds.contains(NoUserId), "TyE40WJKH24")

  // If there are no replies, then there are no frequent posters. [poster_stats]
  require(lastReplyById.isDefined || frequentPosterIds.isEmpty, "TyE40WJKH24")

  def lastReplyAt: Opt[java.util.Date] = lastReplyWhen.map(_.toJavaDate)
}


object PageParts {

  // Private comments (e.g. between mods) and people's bookmarks aren't visible to others.
  // Their post nrs are <= -1001, so there's a quick way to find all not-private page parts.
  val MaxPrivateNr: i32 = -1001
  val MinPublicNr : i32 = 0  // for now. But the title nr will change from 0 to -1?
  // (Values -1 ... -1000 are reserved, sort of.)

  // Letting the page body / original post be number 1 is compatible with Discourse.
  // COULD change to TitleNr = -1. That'd *reduce bug risk* because right now,
  // client side, one always need to remember to use `if (_.isNumber(postNr))` instead of just
  // `if (postNr)` to see if one got a post number or not. [4WKBA20]
  val TitleNr = 0

  val BodyNr = 1
  val FirstReplyNr = 2  // [5FKF0F2]

  val LowestPostNr: Int = TitleNr   // would need to remove if does: [4WKBA20]
  assert(LowestPostNr == 0)

  val NoNr: Int = -1   // would be good to change to 0 instead [4WKBA20]

  val MaxTitleLength = 200  // sync with e2e tests

  def isArticleOrTitlePostNr(nr: PostNr): Boolean =
    nr == PageParts.BodyNr || nr == PageParts.TitleNr



  def lastVisibleReply(posts: Seq[Post]): Option[Post] = {
    val replies: Seq[Post] = posts.filter(post =>
      post.isReply && post.isVisible)  // (96502764)
    //replies.maxOptBy(_.createdAt.getTime) ?? why maxOptBy not found ??
    if (replies.isEmpty) None
    else Some(replies.maxBy(_.createdAt.getTime))
  }


  /** Finds the 3 most frequent posters.
    * Would: If two users have both posted X posts, then, among them, pick the most recent poster?
    */
  def findFrequentPosters(posts: Seq[Post], butWithUpdatedPosts: Seq[Post]): Seq[UserId] = {
    findInterestingPosters(posts = posts, butWithUpdatedPosts).frequentPosterIds
  }


  def findInterestingPosters(posts: Seq[Post], butWithUpdatedPosts: Seq[Post])
          : InterestingPosters = {
    // Tests: [prom_pats_tests]

    val postsWithChanges = postAndChanges(posts, updatedPosts = butWithUpdatedPosts)

    assert(NoUserId == 0)
    var origPostAuthorId = 0
    var lastReplyById = 0
    // The last approved comment, and last posted comment, need not be the same.
    // Currently, for simplicity, we use only the last *created* comment,
    // to show the last replyer.
    var lastReplyCreatedWhen: When = When.Genesis

    for (post <- postsWithChanges) {
      if (post.isOrigPost) origPostAuthorId = post.createdById
      else if (post.isReply && post.isVisible) {
        if (post.createdWhen isAfter lastReplyCreatedWhen) {
          lastReplyById = post.createdById
          lastReplyCreatedWhen = post.createdWhen
          // Later, might sometimes want:  post.approvedWhen.get // [first_last_apr_at]
        }
      }
    }

    val numPostsByUserId = mutable.HashMap[UserId, Int]().withDefaultValue(0)

    for {
      post <- postsWithChanges
      if post.isReply && post.isVisible  // (96502764)
      // Ignore the page creator and the last replyer, because they have their own first-&-last
      // entries in the Users column in the forum topic list. [7UKPF26], and a test [206K94QTD]
      if post.createdById != origPostAuthorId && post.createdById != lastReplyById
    } {
      val numPosts = numPostsByUserId(post.createdById)
      numPostsByUserId(post.createdById) = numPosts + 1
    }

    val userIdsAndNumPostsSortedDesc =
          numPostsByUserId.toSeq.sortBy(userIdAndNumPosts => -userIdAndNumPosts._2)
    val frequentPosterIdsDesc: Seq[PatId] =
          userIdsAndNumPostsSortedDesc.take(3).map(_._1)

    InterestingPosters(
          origPostAuthorId = if (origPostAuthorId == 0) None else Some(origPostAuthorId),
          lastReplyWhen = if (lastReplyById == 0) None else Some(lastReplyCreatedWhen),
          lastReplyById = if (lastReplyById == 0) None else Some(lastReplyById),
          frequentPosterIds = frequentPosterIdsDesc)
  }


  private def postAndChanges(posts: Seq[Post], updatedPosts: Seq[Post]): Seq[Post] = {
    if (updatedPosts.isEmpty)
      return posts

    // The bool says if we've seen the updated-post in `posts` — if not, it's new.
    val changeMap = mutable.HashMap.from(updatedPosts.map(p => p.id -> (p, false)))
    val oldAndUpdatedPosts = posts map { post =>
      changeMap.get(post.id) map { case (updatedPost, _) =>
        // Remember we've seen the updated post.
        changeMap.update(post.id, (updatedPost, true))
        // Use it instead of the old.
        updatedPost
      } getOrElse post
    }

    // Add any new posts (the ones we didn't mark as seen above).
    val newPosts = changeMap.values.filter(postAndSeen => !postAndSeen._2).map(_._1)

    // Order is undefined? Still, appending makes more sense?
    oldAndUpdatedPosts ++ newPosts
  }
}


case class PreLoadedPageParts(
  override val pageMeta: PageMeta,
  allPosts: Vec[Post],
  override val origPostReplyBtnTitle: Opt[St] = None,
  override val origPostVotes: OrigPostVotes = OrigPostVotes.Default,
  anyPostOrderNesting: Option[PostsOrderNesting] = None,
  )
  extends PageParts {

  require(pageMeta.pageId != NoPageId || allPosts.isEmpty, "TyE3J05WKDT5")

  def pageId: PageId = pageMeta.pageId
  def exists: Bo = pageMeta.pageId != NoPageId

  override def postsOrderNesting: PostsOrderNesting =
    anyPostOrderNesting getOrElse {
      if (pageMeta.pageType == PageType.EmbeddedComments)
        PostsOrderNesting.DefaultForEmbComs
      else
        PostsOrderNesting.Default
    }

}


private case class AncestorsChildsAndDepth(
  depth: i32,
  ancestors: Vec[Post],
  childsSorted: Vec[Post],
  )


/** The parts of a page are 1) posts: any title post, any body post, and any comments,
  * and 2) people, namely those who have authored or edited the posts.
  *
  * Should be immutable. If backed by the database, a serializable isolation level
  * transaction should be used.
  *
  * TODO move to debiki-server instead?
  */
// REFACTOR  combine PageDao and PagePartsDao into the same class, "PageDao". [ONEPAGEDAO]
//  + see above TODO.
abstract class PageParts {

  private lazy val postsByNr: collection.Map[PostNr, Post] = {
    val postsMap = mutable.HashMap[PostNr, Post]()
    for (post <- allPosts) {
      postsMap.put(post.nr, post)
    }
    postsMap
  }

  def pageMeta: PageMeta

  def exists: Boolean

  def postsOrderNesting: PostsOrderNesting

  def origPostVotes: OrigPostVotes

  def origPostReplyBtnTitle: Option[String]

  def enableDisagreeVote: Bo = true


  private lazy val childrenSortedByParentNr: collection.Map[PostNr, AncestorsChildsAndDepth] = {
    WOULD_OPTIMIZE // specify the capacity, both the sibling arrays and the map But how?
    WOULD_OPTIMIZE // use a MutArrBuf for the children lists, until done sorting?
    val childMap = mutable.HashMap[PostNr, AncestorsChildsAndDepth]()
    for {
      post <- allPosts
      if !post.isTitle && !post.isOrigPost
      if post.parentNr isNot post.nr
    } {
      val parentNrOrNoNr = post.parentNr getOrElse PageParts.NoNr
      val node: AncestorsChildsAndDepth = childMap.get(parentNrOrNoNr) match {
        case Some(node: AncestorsChildsAndDepth) =>
          node.copy(childsSorted = node.childsSorted :+ post)
        case None =>
          val ancestorPosts = ancestorsParentFirstOf(post)
          // The title post and orig post are at depth 0. Top level replies at depth 1.
          val ancLen = ancestorPosts.length
          val depth =
                if (ancestorPosts.lastOption.exists(_.isOrigPost)) {
                  assert(ancLen >= 1, "TyE7MJ4XT4")
                  ancLen
                }
                else {
                  // This comment thread doesn't start at the Orig Post.
                  // Let's let its topmost comment have depth 1 anyway, since only
                  // the title and orig post are supposed to have depth 0, right.
                  ancLen + 1
                }
          AncestorsChildsAndDepth(
                ancestors = ancestorPosts,
                // Not yet sorted, but soon; see Post.sortPosts() just below.
                childsSorted = Vec(post),
                depth = depth)
      }

      childMap.put(parentNrOrNoNr, node)
    }

    (childMap.mapValues { node =>
      val sortOrder = postsOrderNesting.sortOrder.atDepth(node.depth)
      val childsSortedForReal = Post.sortPosts(node.childsSorted, sortOrder)
      node.copy(childsSorted = childsSortedForReal)
    }).toMap
  }


  def lastPostButNotOrigPost: Option[Post] =
    postByNr(highestReplyNr)


  // Could rename to highestPostNrButNotOrigPost? Because includes chat comments & messages too.
  def highestReplyNr: Option[PostNr] = {
    if (allPosts.isEmpty)
      return None
    val maxNr = allPosts.map(_.nr).max
    if (PageParts.isArticleOrTitlePostNr(maxNr)) None
    else Some(maxNr)
  }

  def pageId: PageId

  def titlePost: Option[Post] = postByNr(PageParts.TitleNr)

  def parentlessRepliesSorted: immutable.Seq[Post] =
    childrenSortedOf(PageParts.NoNr)

  lazy val progressPostsSorted: immutable.Seq[Post] = {
    val progressPosts = allPosts filter { post =>
      !PageParts.isArticleOrTitlePostNr(post.nr) && post.shallAppendLast
    }
    // Progress posts are always by time ascending. [PROGRTIME]
    Post.sortPosts(progressPosts, PostSortOrder.OldestFirst)
  }

  def allPosts: Vec[Post]

  def postByNr(postNr: PostNr): Option[Post] = postsByNr.get(postNr)
  def postByNr(postNr: Option[PostNr]): Option[Post] = postNr.flatMap(postsByNr.get)
  def thePostByNr(postNr: PostNr): Post = postByNr(postNr).getOrDie(
    "TyE9PKG3", s"Post nr $postNr on page $pageId not found")

  def postById(postId: PostId): Option[Post] = {
    COULD_OPTIMIZE // add a (lazy) by-id map?
    postsByNr.values.find(_.id == postId)
  }

  def thePostById(postId: PostId): Post = postById(postId).getOrDie(
    "EsE6YKG72", s"Post id $postId on page $pageId not found")

  def body: Option[Post] = postByNr(BodyNr)
  def theBody: Post = thePostByNr(BodyNr)
  def theTitle: Post = thePostByNr(TitleNr)

  /** Finds all of postNrs. If any single one (or more) is missing, returns Error. */
  def getPostsAllOrError(postNrs: Set[PostNr]): immutable.Seq[Post] Or One[PostNr] = {
    Good(postNrs.toVector map { nr =>
      postsByNr.getOrElse(nr, {
        return Bad(One(nr))
      })
    })
  }

  def numRepliesTotal: Int = allPosts.count(_.isReply)
  def numPostsTotal: Int = allPosts.length

  lazy val numRepliesVisible: Int = allPosts count { post =>
    post.isReply && post.isVisible
  }

  lazy val numOrigPostRepliesVisible: Int = allPosts count { post =>
    post.isOrigPostReply && post.isVisible
  }


  lazy val (numLikes, numWrongs, numBurys, numUnwanteds) = {
    var likes = 0
    var wrongs = 0
    var burys = 0
    var unwanteds = 0
    allPosts.filter(_.isVisible) foreach { post =>
      likes += post.numLikeVotes
      wrongs += post.numWrongVotes
      burys += post.numBuryVotes
      unwanteds += post.numUnwantedVotes
    }
    (likes, wrongs, burys, unwanteds)
  }


  lazy val lastVisibleReply: Option[Post] = {
    PageParts.lastVisibleReply(allPosts)
  }


  def frequentPosterIds: Seq[UserId] = {
    PageParts.findFrequentPosters(
          this.allPosts, butWithUpdatedPosts = Nil)
  }


  /** Returns the index of `post` among its siblings, the first sibling is no 0.
    * Also tells if there are any non-deleted sibling post trees sorted after `post`.
    */
  def siblingIndexOf(post: Post): (Int, Boolean) = {
    val siblings: Seq[Post] = post.parentNr match {
      case None => parentlessRepliesSorted
      case Some(parentNr) => childrenSortedOf(parentNr)
    }

    var index = 0
    var result = -1
    while (index < siblings.length) {
      val sibling = siblings(index)
      if (sibling.nr == post.nr) {
        dieIf(result != -1, "DwE4JPU7")
        result = index
      }
      else if (result != -1) {
        if (!sibling.isDeleted || hasNonDeletedSuccessor(sibling.nr))
          return (result, true)
      }
      index += 1
    }
    (result, false)
  }


  // (Maybe exclude bookmarks?  Currently [bookmarks_filtered_out] elsewhere)
  def childrenSortedOf(postNr: PostNr): immutable.Seq[Post] =
    childrenSortedByParentNr.get(postNr).map(_.childsSorted) getOrElse Nil


  // (Exclude bookmarks?  Currently [bookmarks_filtered_out] elsewhere)
  def descendantsOf(postNr: PostNr): immutable.Seq[Post] = {
    val childs = childrenSortedOf(postNr)
    val pending = ArrayBuffer[Post](childs: _*)
    val successors = ArrayBuffer[Post]()
    while (pending.nonEmpty) {
      val next = pending.remove(0)
      dieIf(successors.exists(_.nr == next.nr),
        "DwE9FKW3", s"Cycle detected on page '$pageId'; it includes post nr ${next.nr}")
      successors.append(next)
      pending.append(childrenSortedOf(next.nr): _*)
    }
    successors.toVector
  }


  def hasNonDeletedSuccessor(postNr: PostNr): Boolean = {
    COULD_OPTIMIZE // bad O(?) complexity when called on each node, like
    // ReactJson.pageToJsonImpl does — O(n*n)? Could start at the leaves and work up instead
    // and cache the result -> O(n).
    childrenSortedOf(postNr) exists { child =>
      !child.deletedStatus.isDeleted || hasNonDeletedSuccessor(child.nr)
    }
  }


  def parentOf(post: Post): Option[Post] = {
    if (post.pageId != pageId) {
      // Could fail an assertion, if debug.
      return None
    }
    post.parentNr.flatMap(postByNr)
  }


  def depthOf(postNr: PostNr): Int =
    ancestorsParentFirstOf(postNr).length


  /** The post must exist. */
  def ancestorsParentFirstOf(postNr: PostNr): Vec[Post] = {
    ancestorsParentFirstOf(thePostByNr(postNr))
  }


  /** Starts with postNr's parent. Dies if cycle. */
  def ancestorsParentFirstOf(post: Post): Vec[Post] = {
    val ancestors = MutArrBuf[Post]()
    var curPost: Opt[Post] = Some(post)
    var numLaps = 0
    while ({
      curPost = parentOf(curPost.get)
      curPost.nonEmpty
    }) {
      numLaps += 1
      val theCurPost = curPost.get
      // To mostly avoid O(n^2) time, don't check for cycles so very often. [On2]
      if ((numLaps % 1000) == 0) {
        val cycleFound = ancestors.exists(_.nr == theCurPost.nr)
        SHOULD // use bugWarn instead, and return Vec.empty?
        dieIf(cycleFound, "TyEPOSTCYCL",
              s"Post cycle on page $pageId around post nr ${theCurPost.nr}")
      }
      ancestors.append(theCurPost)
    }
    ancestors.to(Vec)
  }


  def findCommonAncestorNr(postNrs: Seq[PostNr]): PostNr = {
    TESTS_MISSING
    if (postNrs.isEmpty || postNrs.contains(PageParts.NoNr))
      return PageParts.NoNr

    val postNrsVisited = mutable.HashSet[PostNr]()

    // But this cycle check isn't needed? ancestorsOf won't return a cycle; it dieIf instead.
    def dieIfCycle(postNr: PostNr): Unit = {
      dieIf(postNrsVisited contains postNr,
        "TyEPSTANCCYCL", s"Post parent nrs form a cycle on page $pageId, these nrs: $postNrsVisited")
      postNrsVisited.add(postNr)
    }

    val firstPost = thePostByNr(postNrs.head)
    WOULD_OPTIMIZE // don't use lists here
    var commonAncestorNrs: Seq[PostNr] =
          firstPost.nr :: ancestorsParentFirstOf(firstPost.nr).map(_.nr).toList
    for (nextPostNr <- postNrs.tail) {
      val nextPost = thePostByNr(nextPostNr)
      var ancestorNrs = nextPost.nr :: ancestorsParentFirstOf(nextPost.nr).map(_.nr).toList
      var commonAncestorFound = false
      postNrsVisited.clear()
      while (ancestorNrs.nonEmpty && !commonAncestorFound) {
        val nextAncestorNr = ancestorNrs.head
        dieIfCycle(nextAncestorNr)
        if (commonAncestorNrs.contains(nextAncestorNr)) {
          commonAncestorNrs = commonAncestorNrs.dropWhile(_ != nextAncestorNr)
          commonAncestorFound = true
        }
        else {
          ancestorNrs = ancestorNrs.tail
        }
      }
      if (ancestorNrs.isEmpty)
        return NoNr
    }
    commonAncestorNrs.head
  }

}
