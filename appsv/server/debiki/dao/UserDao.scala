/**
 * Copyright (C) 2012 Kaj Magnus Lindberg (born 1979)
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

package debiki.dao

import scala.collection.Seq
import com.debiki.core._
import debiki.EdHttp._
import debiki.JsonMaker.NotfsAndCounts
import talkyard.server.security.{BrowserId, ReservedNames, SidStatus}
import java.{util => ju}
import play.api.libs.json.{JsArray, JsObject}
import play.{api => p}
import scala.collection.{immutable, mutable}
import Prelude._
import org.scalactic.{Bad, ErrorMessage, Good, Or}
import scala.collection.mutable.ArrayBuffer
import talkyard.server._
import talkyard.server.dao.StaleStuff
import talkyard.server.authn.{Join, Leave, JoinOrLeave, StayIfMaySee}
import talkyard.server.authz.{AuthzCtxOnPats, AuthzCtxOnAllWithReqer, AuthzCtxWithReqer}
import talkyard.server.authz.{StaffReqrAndTgt, ReqrAndTgt, PatAndPrivPrefs}


case class LoginNotFoundException(siteId: SiteId, userId: UserId)
  extends QuickMessageException(s"User $siteId:$userId not found")


case class ReadMoreResult(
  numMoreNotfsSeen: Int,
  )


case class EditMemberCtx(
  tx: SiteTx,
  statleStuff: StaleStuff,
  member: MemberVb,
  reqer: User)


private case class JoinLeavePageDbResult(
  patIdsCouldntJoin: Set[PatId],
  pageMeta: PageMeta,
  anyChange: Bo)


trait UserDao {
  self: SiteDao =>

  import self.context.security

  def addUserStats(moreStats: UserStats)(tx: SiteTransaction): Unit = {
    // Exclude superadmins. Maybe should incl system? [EXCLSYS]
    if (NoUserId < moreStats.userId && moreStats.userId < Participant.LowestNormalMemberId)
      return

    val stats = tx.loadUserStats(moreStats.userId) getOrElse {
      logger.warn(s"s$siteId: Stats missing for user ${moreStats.userId} [TyE2W5Z8A4]")
      return
    }

    val newStats = stats.addMoreStats(moreStats)
    SHOULD // if moreStats replies to chat message or discourse topic, then update
    // num-chat/discourse-topics-replied-in.
    SHOULD // update num-topics-entered too
    tx.upsertUserStats(newStats)
  }


  def insertInvite(invite: Invite): Unit = {
    readWriteTransaction { tx =>
      tx.insertInvite(invite)
    }
  }


  /** Returns: (CompleteUser, Invite, hasBeenAcceptedAlready: Boolean)
    */
  def acceptInviteCreateUser(secretKey: String, browserIdData: BrowserIdData)
        : (UserInclDetails, Invite, Boolean) = {

    val result = readWriteTransaction { tx =>
      var invite = tx.loadInviteBySecretKey(secretKey) getOrElse throwForbidden(
        "DwE6FKQ2", "Bad invite key")

      invite.acceptedAt foreach { acceptedAt =>
        val millisAgo = (new ju.Date).getTime - acceptedAt.getTime
        // For now: If the invitation is < 1 day old, allow the user to log in
        // again via the invitation link. In Discourse, this timeout is configurable.
        if (millisAgo < 24 * 3600 * 1000) {
          val user = tx.loadTheUserInclDetails(invite.userId getOrDie "TyE6FKEW2")
          return (user, invite, true)
        }

        throwForbidden("DwE0FKW2", "You have joined the site already, but this link has expired")
      }

      SECURITY // Rather harmless. What if Mallory signed up with the same email, but it's not his email?
      // He couldn't verify the email address, but can block the real user from accepting
      // the invite? [5UKHWQ2])
      if (tx.loadUserByPrimaryEmailOrUsername(invite.emailAddress).isDefined)
        throwForbidden("DwE8KFG4", o"""You have joined this site already, so this
             join-site invitation link does nothing. Thanks for clicking it anyway""")

      val userId = tx.nextMemberId
      val emailAddrBeforeAt = invite.emailAddress.split("@").headOption.getOrDie(
        "TyE500IIEA5", "Invalid invite email address")

      // Wait with allowing [.-] until canonical usernames implemented. [CANONUN]
      val username = Participant.makeOkayUsername(
          emailAddrBeforeAt, allowDotDash = false, tx.isUsernameInUse) getOrElse {
        // This means couldn't generate a username. That'd be impossibly bad luck, since we
        // try with random numbers of size up to 10^19 many times.
        throwBadReq("TyEBADLUCK", o"""Couldn't generate a unique username. Reload the page
            to try again, and I wish you good luck""")
      }

      dieIfBad(Validation.checkUsername(username),
        "TyE4WKBA2", errMsg => s"I generated an invalid username given '$emailAddrBeforeAt': $errMsg")

      val anyGroups: Option[Group] = invite.addToGroupIds.headOption flatMap { groupId =>
        val group = tx.loadGroup(groupId).getOrDie( // there's a foreign key
          "TyE305KD45", s"s$siteId: Group $groupId gone — cannot add invited member to group")
        dieIf(group.isBuiltIn, "TyE05KRJR204")  // [305FDF4R]
        if (group.isDeleted) None
        else if (group.isStaff) None  // for now, may not join such groups [305FDF4R]
        else Some(group)
      }

      // Invalidate other invites to the same user.
      val otherInvitesSameUser: Seq[Invite] = tx.loadInvitesSentTo(invite.emailAddress)
      for (otherInvite <- otherInvitesSameUser; if otherInvite.secretKey != invite.secretKey) {
        tx.updateInvite(
          otherInvite.copy(
            invalidatedAt = Some(tx.now.toJavaDate)))
      }

      logger.debug(
        s"s$siteId: Creating invited user @$username, email addr: ${invite.emailAddress} [TyD6KWA02]")

      var newUser = invite.makeUser(userId, username = username, tx.now.toJavaDate)
      val inviter = tx.loadParticipant(invite.createdById) getOrDie "DwE5FKG4"
      if (inviter.isStaff) {
        newUser = newUser.copy(
          isApproved = Some(true),
          reviewedAt = Some(tx.now.toJavaDate),
          reviewedById = Some(invite.createdById))
      }

      invite = invite.copy(acceptedAt = Some(tx.now.toJavaDate), userId = Some(userId))

      // COULD loop and append 1, 2, 3, ... until there's no username clash.
      tx.deferConstraints()
      tx.insertMember(newUser)
      tx.insertUserEmailAddress(newUser.primaryEmailInfo getOrDie "EdE3PDKR20")
      tx.insertUsernameUsage(UsernameUsage(
        newUser.usernameLowercase,  // [CANONUN]
        inUseFrom = tx.now, userId = newUser.id))
      tx.upsertUserStats(UserStats.forNewUser(
        newUser.id, firstSeenAt = tx.now, emailedAt = Some(invite.createdWhen)))
      anyGroups.foreach { group =>
        tx.addGroupMembers(group.id, Set(newUser.id))
      }
      tx.updateInvite(invite)
      tx.insertAuditLogEntry(makeCreateUserAuditEntry(newUser, browserIdData, tx.now))

      (newUser, invite, false)
    }

    // It'd be a bit complicated to find out precisely which groups need to be uncached
    // (depends on e.g. which trust level the user got — some groups can auto-grant
    // a trust level (not yet impl)). So, for now, uncache all built-in groups.
    uncacheBuiltInGroups()

    result._2.addToGroupIds foreach { groupId =>
      memCache.remove(groupMembersKey(groupId))  // [inv2groups]
    }

    // Need not — happens lazily on first access:  [auto_join_chats]
    // _addPinnedGlobalChatsToWatchbar(newUser, tx)

    result
  }


  def editUserIfAuZ(memberId: UserId, doWhat: EditUserAction, byWho: Who): Unit = {
    // E2e tested here: [5RBKWEF8]
    val now = globals.now()
    val emailsToSend = ArrayBuffer[Email]()
    val lang = getWholeSiteSettings().languageCode

    writeTx { (tx, _) =>
      val byMember = tx.loadTheUser(byWho.id)
      val memberBefore = tx.loadTheUserInclDetails(memberId)

      // (Later: Better err msg if is in fact staff. [pseudonyms_later])
      throwForbiddenIf(!byMember.isStaff,
            "TyENSTFF5026", "Only staff can do this")

      throwForbiddenIf(memberBefore.isAdmin && !byMember.isAdmin,
            "TyENADM0246", "Non-admins cannot reconfigure admins")

      val memberAfter = copyEditUser(memberBefore, doWhat, byMember, now) getOrIfBad { errorMessage =>
        throwForbidden("TyE4KBRW2", errorMessage)
      }

      lazy val (site, siteOrigin, siteHostname) = theSiteOriginHostname(tx)

      // Sometimes need to do some more things. [2BRUI8]
      import EditUserAction._
      doWhat match {
        case SetEmailVerified | SetEmailUnverified =>
          val userEmailAddrs = tx.loadUserEmailAddresses(memberId)
          val addr = userEmailAddrs.find(_.emailAddress == memberBefore.primaryEmailAddress) getOrDie(
              "TyE2FKJ6W", s"s$siteId: No primary email addr, user id $memberId")
          val addrUpdated = addr.copy(
            verifiedAt = if (doWhat == EditUserAction.SetEmailVerified) Some(now) else None)
          tx.updateUserEmailAddress(addrUpdated)

        case SetApproved if memberAfter.canReceiveEmail =>
          // Just send an email — no need to create any notification and show in the
          // new member's notification list, right.  TyTE2E05WKF2
          val emailTexts = talkyard.server.emails.out.Emails.inLanguage(lang)
          emailsToSend.append(Email.createGenId(
                EmailType.YourAccountApproved,
                createdAt = tx.now,
                sendTo = memberAfter.primaryEmailAddress,
                toUserId = Some(memberAfter.id),
                subject = s"[$siteHostname] Account approved",
                bodyHtml = emailTexts.accountApprovedEmail(
                      memberAfter,
                      siteHostname = siteHostname,
                      siteOrigin = siteOrigin)))

        case _ =>
          // Noop.
      }

      AUDIT_LOG /* val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.   ... what? new AuditLogEntryType enums, or one single EditUser enum,
                                              with an int val = the EditMemberAction int val ?
        doerTrueId = byWho.trueId,
        doneAt = now.toJavaDate,
        browserIdData = byWho.browserIdData,
        browserLocation = None)*/

      tx.updateUserInclDetails(memberAfter)
      //tx.insertAuditLogEntry(auditLogEntry)
    }

    globals.sendEmails(emailsToSend, siteId)

    // Later: If is-admin/moderator is visible somehow next to the user's name/avatar, then need to
    // uncache pages where hens name appears (if admin/moderator status got changed). [5KSIQ24]

    removeUserFromMemCache(memberId)

    // In case the user was promoted/demoted to moderator or admin, or a different
    // trust level, update hens group membership. (Could add logic to do this only if
    // necessary, but that'd be extra code and bug risk?)
    uncacheOnesGroupIds(Seq(memberId))
    uncacheBuiltInGroups()
  }


  /** Don't place this fn in class Member, because if invoked without also doing
    * the other things in editMember() above [2BRUI8], the database will be left in an
    * inconsistent state. So, this fn should be accessible only to editMember() above.
    */
  private def copyEditUser(member: UserInclDetails, doWhat: EditUserAction,
        byMember: User, now: When): UserInclDetails Or ErrorMessage = {
    import EditUserAction._
    def someNow = Some(now.toJavaDate)
    def someById = Some(byMember.id)

    def checkNotPromotingOrDemotingOneself(): Unit = {
      // Bad idea to let people accidentally click "Revoke admin" on their profile, and lose access?
      if (member.id == byMember.id)
        throw new QuickMessageException("Cannot change one's own is-admin / moderator status")
    }

    def checkNotPromotingSuspended(): Unit = {
      if (member.isSuspendedAt(now.toJavaDate))
        throw new QuickMessageException("Cannot promote suspended users to admin or moderator")
    }

    try Good(doWhat match {
      case SetEmailVerified =>
        if (member.primaryEmailAddress.isEmpty)
          return Bad("Cannot set primary email addr to verified: No primary email addr specified")
        member.copy(emailVerifiedAt = someNow)
      case SetEmailUnverified =>
        member.copy(emailVerifiedAt = None)
      case SetApproved =>
        member.copy(isApproved = Some(true), reviewedAt = someNow, reviewedById = someById)
      case SetUnapproved =>
        member.copy(isApproved = Some(false), reviewedAt = someNow, reviewedById = someById)
      case ClearApproved =>
        member.copy(isApproved = None, reviewedAt = None, reviewedById = None)
      case SetIsAdmin =>
        checkNotPromotingSuspended()
        checkNotPromotingOrDemotingOneself()
        member.copy(isAdmin = true, isModerator = false)
      case SetNotAdmin =>
        checkNotPromotingOrDemotingOneself()
        member.copy(isAdmin = false)
      case SetIsModerator =>
        checkNotPromotingSuspended()
        checkNotPromotingOrDemotingOneself()
        member.copy(isModerator = true, isAdmin = false)
      case SetNotModerator =>
        checkNotPromotingOrDemotingOneself()
        member.copy(isModerator = false)
    })
    catch {
      case ex: QuickMessageException =>
        Bad(ex.message)
    }
  }


  def lockUserTrustLevel(memberId: UserId, newTrustLevel: Opt[TrustLevel]): U = {

    // ----- Step 1/3:  Update database

    val (membAft, promoted, chatsPatLeft) = writeTx { (tx, staleStuff) =>
      val membBef = tx.loadTheUserInclDetails(memberId)
      val membAft = membBef.copy(lockedTrustLevel = newTrustLevel)
      tx.updateUserInclDetails(membAft)
      val promoted = membAft.effectiveTrustLevel.isAbove(membBef.effectiveTrustLevel)
      val demoted = membAft.effectiveTrustLevel.isBelow(membBef.effectiveTrustLevel)
      if (promoted) {
        (membAft, true, Nil)
      }
      else if (demoted) {
        val chatsPatLeft = _leavePagesMayNotSee_updateDb(membAft)(tx, staleStuff)   // [leave_opn_cht]
        (membAft, false, chatsPatLeft)
      }
      else {
        // Nothing changed — trust level locked at the current trust level, right.
        // (But better continue below, so the mem cache gets updated, although
        // theoretically shouldn't matter.)
        (membAft, false, Nil)
      }
    }

    // ----- Step 2/3:  Update mem cache

    COULD // use staleStuff above instead? (Then this step 2/3 disappears.)

    removeUserFromMemCache(memberId)
    // Now the user might have joined / left trust level groups.
    uncacheOnesGroupIds(Seq(memberId))
    uncacheBuiltInGroups()

    // ----- Step 3/3:  Update watchbar

    // Do this outside the tx and after having refreshed the cache above.
    if (promoted) {
      // [join_opn_cht]
      BUG // Harmless. Publishes presence, although the user might be away. [presence_bug]
      _addPinnedGlobalChatsToWatchbar(membAft)
    }
    else if (chatsPatLeft.nonEmpty) {
      for (pageMeta <- chatsPatLeft) {
        _joinLeavePage_updateWatchbar(
            userIds = Set(memberId), couldntAdd = Set.empty, Remove, pageToJoinLeave = pageMeta)
      }
    }
  }


  def lockUserThreatLevel(memberId: UserId, newThreatLevel: Option[ThreatLevel]): Unit = {
    readWriteTransaction { tx =>
      val member: UserInclDetails = tx.loadTheUserInclDetails(memberId)
      val memberAfter = member.copy(lockedThreatLevel = newThreatLevel)
      tx.updateUserInclDetails(memberAfter)
    }
    removeUserFromMemCache(memberId)
    // (There are no threat level groups to uncache.)
  }


  def lockGuestThreatLevel(guestId: UserId, newThreatLevel: Option[ThreatLevel]): Unit = {
    readWriteTransaction { tx =>
      val guest = tx.loadTheGuest(guestId)
      ??? // lock both ips and guest cookie
    }
    removeUserFromMemCache(guestId)
  }


  /** Banning works like suspending, just some UI buttons that will (later) read "Banned"
    * instead of "Suspended", so it's more clear that the person won't be coming back.
    * And the person can't log in and view their old posts — but you can if you're
    * only suspended.
    */
  def banAuthorOf(post: Post, reason: St, bannedById: UserId)(tx: SiteTx, ss: StaleStuff,
          ): Opt[Pat] = {
    // (We've already checked if the banner may see the page. [auz_banner_of_auhtor])

    // (Don't throw anything from here — that'd prevent the janitor actor from ever
    // carrying out this mod task, and mod tasks later in the queue.)

    val patBef: Pat = tx.loadPatVb(post.createdById) getOrElse {
      logger.error(s"s$siteId: Can't find author of post ${post.id} [TyEBAN0ATR]")
      return None
    }

    val patAft: Pat = patBef match {
      case guestBef: Guest =>
        UNTESTED
        // Guests don't have real user accounts — we'll ban their
        // unauthenticated guest account, and their ip addr too for a while.
        val auditLogEntry: Opt[AuditLogEntry] = tx.loadCreatePostAuditLogEntry(post.id)
        if (auditLogEntry.isEmpty)
          logger.warn(o"""s$siteId: No audit log entry, when blocking author of post ${post.id},
              who supposedly is a guest — so can't block IP addr [TyW2WKF6]""")

        val guestAft = this.blockGuestSkipAuZ(
              guestBef, auditLogEntry.map(_.browserIdData),
              ThreatLevel.SevereThreat, blockerId = bannedById)(tx)

        guestAft getOrElse guestBef // guestAft is None if already banned

      case userBef: UserVb =>
        _suspendOrBan(userBef, until = new ju.Date(Pat._BanMagicEpochMs),
              reason = reason, suspendedById = bannedById)(tx, ss) getOrIfBad { err=>
          logger.warn(s"s$siteId: Cound't ban pat ${patBef.id}: $err [TyEBANATR07]")
          return None
        }

      case anonBef: Anonym =>
        // [How_block_anons]
        logger.warn(s"s$siteId: Baning anonyms hasn't been implemented [TyEBANATR05]")
        return None

      case other =>
        logger.error(s"s$siteId: Can't ban a ${classNameOf(other)} [TyEBANATR03]")
        return None
    }

    Some(patAft)
  }


  def suspendUser(userId: UserId, numDays: i32, reason: St, suspendedById: UserId): U = {
    // If later on banning, by setting numDays = none, then look at [4ELBAUPW2], seems
    // it won't notice someone is suspended, unless there's an end date.
    require(numDays >= 1, "DwE4PKF8")

    val cappedDays = math.min(numDays, 365 * 110)

    writeTx { (tx, staleStuff) =>
      val now = tx.now
      val suspendedTill = new ju.Date(now.millis + cappedDays * MillisPerDay)
      var user: UserVb = tx.loadTheUserInclDetails(userId)
      _suspendOrBan(user, until = suspendedTill, reason = reason,
            suspendedById = suspendedById)(tx, staleStuff) ifBad { err =>
        throwForbidden(err)
      }
    }
  }


  /** If `until` is epoch `_BanMagicEpochMs` the user is considered banned.
    */
  private def _suspendOrBan(userBef: UserVb, until: ju.Date, reason: St,
          suspendedById: UserId)(tx: SiteTx, staleStuff: StaleStuff): Pat Or ErrMsgCode = Good {

      if (userBef.isAdmin)
        return Bad(ErrMsgCode("Cannot suspend admins", "TyE4KEF24"))

      val userAft = userBef.copy(
        suspendedAt = Some(now().toJavaDate),
        suspendedTill = Some(until),
        suspendedById = Some(suspendedById),
        suspendedReason = Some(reason.trim))

      tx.updateUserInclDetails(userAft)
      staleStuff.addPatIds(Set(userAft.id))

      logout(userAft.noDetails, bumpLastSeen = false, anyTx = Some(tx, staleStuff))
      terminateSessions(  // [end_sess]
            forPatId = userAft.id, all = true, anyTx = Some(tx, staleStuff))

      userAft
  }


  def unsuspendUser(userId: UserId): Unit = {
    readWriteTransaction { tx =>
      var user = tx.loadTheUserInclDetails(userId)
      user = user.copy(suspendedAt = None, suspendedTill = None, suspendedById = None,
        suspendedReason = None)
      tx.updateUserInclDetails(user)
    }
    removeUserFromMemCache(userId)
  }


  def blockGuestIfAuZ(postId: PostId, threatLevel: ThreatLevel, blocker: StaffReqrAndTgt)
          : Opt[Pat] = {

    val anyChangedGuest: Option[Guest] = readWriteTransaction { tx =>

      // ----- AuZ

      SECURITY; TESTS_MISSING // No tests for guests?  [block_post_author_e2e_test]
      // There's this though: [mod_bans_guest_app_test].

      val post = loadPostByUniqueId(postId, Some(tx)) getOrElse {
        security.throwIndistinguishableNotFound(s"TyE0SEE_BLOCKATR_0POST")
      }
      val (maySeeResult, debugCode) =
            this.maySeePost(post, Some(blocker.reqr), maySeeUnlistedPages = true)(tx)
      if (!maySeeResult.may) {
        security.throwIndistinguishableNotFound(s"TyE0SEE_BLOCKATR_$debugCode")
      }

      // ----- Block

      // (We'll look up by post author id, below, if audit log entry missing.)
      val auditLogEntry: Opt[AuditLogEntry] = tx.loadCreatePostAuditLogEntry(postId)

      if (auditLogEntry.isEmpty)
        logger.warn(o"""s$siteId: No audit log entry, when blocking author of post $postId,
              who supposedly is a guest — so can't block ip addr [TyW2WKF5]""")

      val patId = auditLogEntry.map(_.doerId) getOrElse {
        post.createdById
      }
      // Later: Can theoretically be different, if changed author. [post_authors]
      devDieIf(patId != post.createdById, "TyE603SKL46")

      val guest = tx.loadTheParticipant(patId) match {
        case g: Guest => g
        case _: Anonym =>
          // [How_block_anons]?
          throwBadReq("TyE0GUEST522", "Can't block anonyms, not implemented")
        case x =>
          throwBadReq("TyE0GUEST523", "Author is not a guest user")
      }

      blockGuestSkipAuZ(guest, auditLogEntry.map(_.browserIdData),
            threatLevel, blockerId = blocker.reqr.id)(tx)
    }
    anyChangedGuest.foreach(g => removeUserFromMemCache(g.id))
    anyChangedGuest
  }


  /** Returns any guest whose threat level got changed and should be uncached.
    */
  def blockGuestSkipAuZ(guest: Guest, browserIdData: Opt[BrowserIdData],
        threatLevel: ThreatLevel, blockerId: UserId)(tx: SiteTx): Option[Guest] = {

      // Hardcode 2 & 6 weeks for now. Asking the user to choose # days –> too much for him/her
      // to think about. Block the ip for a little bit shorter time, because might affect
      // "innocent" people.
      val ipBlockedTill =
        Some(new ju.Date(tx.now.millis + OneWeekInMillis * 2))

      val cookieBlockedTill =
        Some(new ju.Date(tx.now.millis + OneWeekInMillis * 6))

      val ipBlock = browserIdData map { brIdData => Block(
        threatLevel = threatLevel,
        ip = Some(brIdData.inetAddress),   // include ip
        browserIdCookie = None,            // skip cookie
        blockedById = blockerId,
        blockedAt = tx.now.toJavaDate,
        blockedTill = ipBlockedTill)
      }

      val browserIdCookieBlock = browserIdData.flatMap(_.idCookie) map { idCookie =>
        Block(
          threatLevel = threatLevel,
          ip = None,                        // skip ip
          browserIdCookie = Some(idCookie), // include cookie
          blockedById = blockerId,
          blockedAt = tx.now.toJavaDate,
          blockedTill = cookieBlockedTill)
      }

      // COULD catch dupl key error when inserting IP block, and update it instead, if new
      // threat level is *worse* [6YF42]. Aand continue anyway with inserting browser id
      // cookie block.
      ipBlock foreach tx.insertBlock
      browserIdCookieBlock foreach tx.insertBlock

      // Update threat level, if new level is worse.
      if (!guest.lockedThreatLevel.exists(_.toInt >= threatLevel.toInt)) {
        // The new threat level is worse than the previous.
        val worseGuest = guest.copy(lockedThreatLevel = Some(threatLevel))
        tx.updateGuest(worseGuest)
        return Some(worseGuest)
      }

      None
  }


  def unblockGuest(postId: PostId, unblockerId: UserId): Unit = {
    val anyChangedGuest = readWriteTransaction { tx =>
      val auditLogEntry: AuditLogEntry = tx.loadCreatePostAuditLogEntry(postId) getOrElse {
        throwForbidden("DwE5FK83", "Cannot unblock guest: No audit log entry, IP unknown")
      }
      tx.unblockIp(auditLogEntry.browserIdData.inetAddress)
      auditLogEntry.browserIdData.idCookie foreach tx.unblockBrowser
      val anyGuest = tx.loadGuest(auditLogEntry.doerId)
      anyGuest flatMap { guest =>
        if (guest.lockedThreatLevel.isEmpty) None
        else {
          tx.updateGuest(guest.copy(lockedThreatLevel = None))
          Some(guest)
        }
      }
    }
    anyChangedGuest.foreach(g => removeUserFromMemCache(g.id))
  }


  def loadAuthorBlocks(postId: PostId): immutable.Seq[Block] = {
    readOnlyTransaction { tx =>
      val auditLogEntry = tx.loadCreatePostAuditLogEntry(postId) getOrElse {
        return Nil
      }
      val browserIdData = auditLogEntry.browserIdData
      tx.loadBlocks(ip = browserIdData.ip, browserIdCookie = browserIdData.idCookie)
    }
  }


  def loadBlocks(ip: String, browserIdCookie: Option[String]): immutable.Seq[Block] = {
    readOnlyTransactionNotSerializable { tx =>
      tx.loadBlocks(ip = ip, browserIdCookie = browserIdCookie)
    }
  }


  /** Loads the true user, which might be a pseudonym (but never an anonym).
    */
  def loadUserAndLevels(who: Who, tx: SiteTransaction): UserAndLevels = {
    val user: Pat = tx.loadTheParticipant(who.id)
    val trustLevel = user.effectiveTrustLevel
    val threatLevel = user match {
      case member: User => member.effectiveThreatLevel
      case guest: Guest =>
        // Somewhat dupl code [2WKPU08], see a bit below.
        val blocks = tx.loadBlocks(ip = who.ip, browserIdCookie = who.idCookie)
        val baseThreatLevel = guest.lockedThreatLevel getOrElse ThreatLevel.HopefullySafe
        val levelInt = blocks.foldLeft(baseThreatLevel.toInt) { (maxSoFar, block) =>
          math.max(maxSoFar, block.threatLevel.toInt)
        }
        ThreatLevel.fromInt(levelInt) getOrDie "EsE8GY2511"
      case group: Group =>
        ThreatLevel.HopefullySafe // for now
      case _ : Anonym =>
        // Should never do things directly as an anonym, only via one's real account.
        // Higher up the stack, we should have replied Forbidden already.
        die("TyE206MRAKG", s"Got an anon: $who")
    }
    UserAndLevels(user, trustLevel, threatLevel)
  }


  def loadThreatLevelNoUser(browserIdData: BrowserIdData, tx: SiteTransaction)
        : ThreatLevel = {
    // Somewhat dupl code [2WKPU08], see just above.
    val blocks = tx.loadBlocks(
      ip = browserIdData.ip, browserIdCookie = browserIdData.idCookie)
    val levelInt = blocks.foldLeft(ThreatLevel.HopefullySafe.toInt) { (maxSoFar, block) =>
      math.max(maxSoFar, block.threatLevel.toInt)
    }
    ThreatLevel.fromInt(levelInt) getOrDie "EsE8GY2522"
  }


  def saveIdentityCreateUser(newUserData: NewUserData, browserIdData: BrowserIdData)
        : (Identity, UserInclDetails) = {
    val (identity, user) = readWriteTransaction { tx =>
      val userId = tx.nextMemberId
      val user: UserInclDetails = newUserData.makeUser(userId, tx.now.toJavaDate)
      val identityId = tx.nextIdentityId
      val identity = newUserData.makeIdentity(userId = userId, identityId = identityId)
      ensureSiteActiveOrThrow(user, tx)
      tx.deferConstraints()
      tx.insertMember(user)
      user.primaryEmailInfo.foreach(tx.insertUserEmailAddress)
      tx.insertUsernameUsage(UsernameUsage(
        usernameLowercase = user.usernameLowercase, // [CANONUN]
        inUseFrom = tx.now, userId = user.id))
      tx.upsertUserStats(UserStats.forNewUser(
        user.id, firstSeenAt = tx.now, emailedAt = None))

      tx.insertIdentity(identity)  // use saveIdentityLinkToUser() instead somehow?

      // Dupl code [2ABKS03R]
      if (newUserData.isOwner) {
        tx.upsertPageNotfPref(PageNotfPref(userId, NotfLevel.WatchingAll, wholeSite = true))
      }

      tx.insertAuditLogEntry(makeCreateUserAuditEntry(user, browserIdData, tx.now))
      AUDIT_LOG
      (identity, user)
    }

    uncacheBuiltInGroups()
    // Also uncache any custom groups, if auto-joins any such groups here.  [inv2groups]
    memCache.fireUserCreated(user.briefUser)
    (identity, user)
  }


  /** Used if a user without any matching identity has been created (e.g. because
    * you signup as an email + password user, or accept an invitation). And you then
    * later on try to login via e.g. a Gmail account with the same email address.
    * Then we want to create a Gmail OpenAuth identity and connect it to the user
    * in the database.
    *
    * But this is only allowed if pat has verified hens email addr for the
    * new identity, so one cannot link one's external identity to someone else's
    * user account.
  REFACTOR; MOVE // to AuthnSiteDaoMixin.scala
    */
  def saveIdentityLinkToUser(userInfo: IdpUserInfo, user: User): Identity = {
    require(user.email.nonEmpty, "DwE3KEF7")
    // We get to here via askIfLinkIdentityToUser()  [ask_ln_acts]  and
    // all code paths should require that the email addr has been verified.
    // Edit: But this is the user in the db — hens email need not have been verified.
    // Instead, we've verified the email addr of the there person logging in now,
    // (the `userInfo` user) and hen has said already that it's ok to link to
    // the existing user account (to `user`), here:
    //   Ok(views.html.login.askIfLinkAccounts(
    //    ...
    //    oldEmailVerified = user.emailVerified
    //    ...)
    TESTS_MISSING  // TyTE2ELN2UNVER
    // So, don't:
    // require(user.emailVerifiedAt.nonEmpty, "DwE5KGE2")

    require(user.isAuthenticated, "DwE4KEF8")
    readWriteTransaction { tx =>
      val identityId = tx.nextIdentityId
      val identity = OpenAuthIdentity(id = identityId, userId = user.id, userInfo)
      tx.insertIdentity(identity)
      addUserStats(UserStats(user.id, lastSeenAt = tx.now))(tx)
      AUDIT_LOG
      identity
    }
  }


  def createPasswordUserCheckPasswordStrong(userData: NewPasswordUserData, browserIdData: BrowserIdData)
        : User = {
    dieIf(userData.ssoId.isDefined, "TyE5BKW02QX")
    security.throwErrorIfPasswordBad(
      password = userData.password.getOrDie("TyE2AKB84"), username = userData.username,
      fullName = userData.name, email = userData.email,
      minPasswordLength = globals.minPasswordLengthAllSites,
      isForOwner = userData.isOwner)
    val user = readWriteTransaction { tx =>
      createPasswordUserImpl(userData, browserIdData, tx).briefUser
    }
    uncacheBuiltInGroups()
    memCache.fireUserCreated(user)
    user
  }


  def createUserForExternalSsoUser(userData: NewPasswordUserData, botIdData: BrowserIdData,
        tx: SiteTransaction): UserInclDetails = {
    dieIf(userData.password.isDefined, "TyE7KHW2G")
    createPasswordUserImpl(userData, botIdData, tx)
  }


  def createPasswordUserImpl(userData: NewPasswordUserData, browserIdData: BrowserIdData,
        tx: SiteTransaction): UserInclDetails = {
    val now = userData.createdAt
    val userId = tx.nextMemberId
    val user = userData.makeUser(userId)
    ensureSiteActiveOrThrow(user, tx)
    tx.deferConstraints()
    tx.insertMember(user)
    user.primaryEmailInfo.foreach(tx.insertUserEmailAddress)
    tx.insertUsernameUsage(UsernameUsage(
        usernameLowercase = user.usernameLowercase, // [CANONUN]
        inUseFrom = now, userId = user.id))
    tx.upsertUserStats(UserStats.forNewUser(
        user.id, firstSeenAt = userData.firstSeenAt.getOrElse(now), emailedAt = None))

    // Dupl code [2ABKS03R]
    // Initially, when the forum / comments site is tiny, it's good to be notified
    // about everything. (isOwner —> it's the very first user, so the site is empty.)
    if (userData.isOwner) {
      tx.upsertPageNotfPref(PageNotfPref(userId, NotfLevel.WatchingAll, wholeSite = true))
    }

    tx.insertAuditLogEntry(makeCreateUserAuditEntry(user, browserIdData, tx.now))
    user
  }


  private def makeCreateUserAuditEntry(member: UserInclDetails, browserIdData: BrowserIdData,
      now: When): AuditLogEntry = {
    AuditLogEntry(
      siteId = siteId,
      id = AuditLogEntry.UnassignedId,
      didWhat = AuditLogEntryType.CreateUser,
      doerTrueId = member.trueId2,
      doneAt = now.toJavaDate,
      browserIdData = browserIdData,
      browserLocation = None)
  }


  def changePasswordCheckStrongEnough(userId: UserId, newPassword: String): Boolean = {
    val newPasswordSaltHash = DbDao.saltAndHashPassword(newPassword)
    readWriteTransaction { tx =>
      var user = tx.loadTheUserInclDetails(userId)
      security.throwErrorIfPasswordBad(
        password = newPassword, username = user.username,
        fullName = user.fullName, email = user.primaryEmailAddress,
        minPasswordLength = globals.minPasswordLengthAllSites, isForOwner = user.isOwner)
      user = user.copy(passwordHash = Some(newPasswordSaltHash))
      tx.updateUserInclDetails(user)
    }
  }


  def loginAsGuest(loginAttempt: GuestLoginAttempt): Guest = {
    val settings = getWholeSiteSettings()
    dieIf(!settings.canLoginAsGuest, "TyE052MAKD3", "Guest login not enabled")
    val user = readWriteTransaction { tx =>
      val guest = tx.loginAsGuest(loginAttempt).guest
      // We don't know if this guest user is being created now, or if it already exists
      // — so upsert (rather than insert? or update?) stats about this guest user.
      // (Currently this upsert keeps the earliest/oldest first/latest dates, see [7FKTU02].)
      tx.upsertUserStats(UserStats(
        guest.id, firstSeenAtOr0 = tx.now, lastSeenAt = tx.now))
      guest
    }
    // Ignore site version, see [pat_cache].
    memCache.put(
          patKey(user.id),
          MemCacheValueIgnoreVersion(user))
    user
  }


  def tryLoginAsMember(loginAttempt: MemberLoginAttempt): Hopefully[MemberLoginGrant] = {
    SECURITY; COULD // add setting that prevents people from logging in by username — because the
    // username is public, can starting guessing passwords directly. (There are rate limits.) Sth like:
    // if (!settings.allowLoginByUsername && loginAttempt.isByUsername) throwForbidden(...)
    // + prevent submitting username, client side.

    val settings = getWholeSiteSettings()
    dieIf(loginAttempt.isInstanceOf[PasswordLoginAttempt] &&
        !settings.canLoginWithPassword, "TyE0PWDLGI602")
    dieIf(loginAttempt.isInstanceOf[OpenAuthLoginAttempt] && settings.enableSso,
        "TyESSO0OAU02")

    val loginGrant = readWriteTransaction { tx =>
      val loginGrant: MemberLoginGrant =
            tx.tryLoginAsMember(loginAttempt, requireVerifiedEmail =
                  settings.requireVerifiedEmail) getOrIfBad { problem =>
              return Bad(problem)
            }

      val user: UserBr = loginGrant.user
      if (user.isBanned)
        throwForbidden("TyEBANND0_", o"""Account banned""")

      if (user.isSuspendedAt(loginAttempt.date)) {
        val userVb = tx.loadTheUserInclDetails(user.id)
        val forHowLong = user.suspendedTill match {
          case None =>
            // Dead code, currently always set if is suspended or banned. [4ELBAUPW2]
            "forever"
          case Some(date) => "until " + toIso8601NoT(date)
        }
        throwForbidden("TyEUSRSSPNDD_", o"""Account suspended $forHowLong,
              reason: ${userVb.suspendedReason getOrElse "?"}""")
      }

      addUserStats(UserStats(user.id, lastSeenAt = tx.now))(tx)
      loginGrant
    }

    // Tiny optimization: Cache user, will need, next http request.
    // Don't save any site cache version, because user specific data doesn't change
    // when site specific data changes. [pat_cache]
    memCache.put(
          patKey(loginGrant.user.id),
          MemCacheValueIgnoreVersion(loginGrant.user))

    Good(loginGrant)
  }


  def logout(pat: Pat, bumpLastSeen: Bo, anyTx: Opt[(SiteTx, StaleStuff)] = None): U = {
    if (bumpLastSeen) writeTxTryReuse(anyTx) { (tx, staleStuff) =>
      addUserStats(UserStats(pat.id, lastSeenAt = tx.now))(tx)
      staleStuff.addPatIds(Set(pat.id))
    }

    UX; COULD // Maybe a WebSocket channel should remember which session & browser it
    // got started from? Rather than only what user. So can disconnect the right browser,
    // instead of all the user's browsers. For example, if impersonating,
    // then, this'll disconnect all current channels of the real person,
    // and mark hen as away (maybe incorrectly).
    // [which_ws_session]
    pubSub.unsubscribeUser(siteId, pat)
  }


  def loadSiteOwner(): Option[UserInclDetails] = {
    readOnlyTransaction { tx =>
      tx.loadOwner()
    }
  }


  def getParticipantsAsMap(userIds: Iterable[UserId]): Map[UserId, Participant] = {
    getParticipantsImpl(userIds).groupByKeepOne(_.id)
  }


  // Change param to Set[UserId] instead?
  def getUsersAsSeq(userIds: Iterable[UserId]): immutable.Seq[Participant] = {
    getParticipantsImpl(userIds).toVector
  }


  private def getParticipantsImpl(userIds: Iterable[UserId]): ArrayBuffer[Participant] = {
    // Somewhat dupl code [5KWE02]. Break out helper function getManyById[K, V](keys) ?
    val usersFound = ArrayBuffer[Participant]()
    val missingIds = ArrayBuffer[UserId]()
    userIds foreach { id =>
      memCache.lookup[Participant](patKey(id)) match {
        case Some(user) => usersFound.append(user)
        case None => missingIds.append(id)
      }
    }
    if (missingIds.nonEmpty) {
      val moreUsers = readOnlyTransaction(_.loadParticipants(missingIds))
      usersFound.appendAll(moreUsers)
    }
    usersFound
  }


  def loadTheMemberInclDetailsById(memberId: UserId): MemberInclDetails =
    readOnlyTransaction(_.loadTheMemberInclDetails(memberId))


  def loadTheUserInclDetailsById(userId: UserId): UserInclDetails =
    readOnlyTransaction(_.loadTheUserInclDetails(userId))


  def loadTheGroupInclDetailsById(groupId: UserId): Group =
    readOnlyTransaction(_.loadGroupInclDetails(groupId)).getOrDie(
      "EdE2WKBG0", s"Group $groupId@$siteId not found")


  def loadUsersInclDetailsById(userIds: Iterable[UserId]): immutable.Seq[UserInclDetails] = {
    readOnlyTransaction(_.loadUsersInclDetailsById(userIds))
  }


  def loadMembersVbMaySeeByRef(refs: Iterable[PatRef], authzCtx: AuthzCtxOnPats): ImmSeq[MemberVb] = {
    // Currently everyone may see all participants, so, ignore authzCtx for now.  [private_pats]
    readTx(_.loadMembersVbByRef(refs))
  }


  /** Excludes users with profiles hidden / unlistable,  so can't create
    * a list of members, by typing '@' and looking at the result.
    */
  def loadUserMayListByPrefix(prefix: St, caseSensitive: Bo, limit: i32, reqr: Pat)
        : ImmSeq[PatAndPrivPrefs] = {

    // See also: this.listUsernamesOnPage().
    COULD_OPTIMIZE // needn't load throw away not-needed fields. [ONLYNAME]
    COULD_OPTIMIZE // cache, sth like:
    //memCache.lookup[immutable.Seq[User]](
    //  membersByPrefixKey(prefix, "u"),
    //  orCacheAndReturn = Some(readOnlyTransaction(_.loadUsersWithPrefix(prefix)))).get
    // BUT then there'd be a DoS attack: iterate through all prefixes and exhaust
    // the cache. Note that there's a million? Unicode chars, so restricting the
    // prefix length to <= 2 chars won't work (cache size 1e6 ^ 2 = 1e12).
    // But what about caching only ascii?
    val usersTooMany: immutable.Seq[User] = readTx(
          _.loadUsersWithUsernamePrefix(prefix, caseSensitive = caseSensitive, limit = limit))

    val patsPrefsMayList: ImmSeq[PatAndPrivPrefs] = authz.PrivPrefs.filterMayList(
          usersTooMany, reqr, dao = this)

    patsPrefsMayList
  }


  def getTheUser(userId: UserId, anyTx: Opt[(SiteTx, StaleStuff)] = None): User = {
    val anyUser = anyTx match {
      case Some((tx, _)) => tx.loadUser(userId)
      case None => getUser(userId)
    }
    anyUser.getOrElse(throw UserNotFoundException(userId))
  }


  def getUser(userId: UserId): Option[User] = {
    dieIf(userId < Participant.LowestMemberId, "TyE2LOWUID", s"Too low user id: $userId < ${
          Participant.LowestMemberId}")
    getParticipant(userId).map(_ match {
      case user: User => user
      case _: Group => throw GotAGroupException(userId, wantedWhat = "a user")
      case _ => die("TyE2AKBP067")
    })
  }


  def getParticipantByRef(ref: String): Option[Participant] Or ErrorMessage = {
    parseRef(ref, allowPatRef = true) map getParticipantByParsedRef
  }


  def getParticipantByParsedRef(ref: ParsedRef): Option[Participant] = {
    // username: and userid: refs must be to users (not guests or groups).
    COULD // return Bad("Not a user, but a guest/group: __")
    val returnBadUnlessIsUser = (pat: Opt[Pat]) =>
      if (pat.exists(!_.isUserNotGuest))
        return None  // Bad("Not a user but a ${}: ${ref}")

    val returnBadUnlessGroup = (pat: Opt[Pat]) =>
      if (pat.exists(!_.isGroup))
        return None  // Bad("Not a group but a ${}: ${ref}")

    ref match {
      case ParsedRef.ExternalId(extId) =>
        getParticipantByExtId(extId)
      case ParsedRef.TalkyardId(tyId) =>
        tyId.toIntOption.flatMap(id => getParticipant(id))
      case ParsedRef.UserId(id) =>
        val pat = getParticipant(id)
        returnBadUnlessIsUser(pat)
        pat
      case ParsedRef.SingleSignOnId(ssoId) =>
        getMemberBySsoId(ssoId)
      case ParsedRef.Username(username) =>
        val pat = getMemberByUsername(username)
        returnBadUnlessIsUser(pat)
        pat
      case ParsedRef.Groupname(username) =>
        val pat = getMemberByUsername(username)
        returnBadUnlessGroup(pat)
        pat
      //case ParsedRef.Membername(membername) =>
      // val pat = getMemberByUsername(username)
      // returnBadUnlessUserOrGroup(pat)
      // pat
    }
  }


  def getTheGroupOrThrowClientError(groupId: UserId): Group = {
    COULD // instead lookup all groups: allGroupsKey  [LDALGRPS]
    throwBadRequestIf(groupId <= MaxGuestId, "TyE30KMRAK", s"User id $groupId is a guest id")
    getParticipant(groupId) match {
      case Some(g: Group) => g
      case Some(x) => throwForbidden("TyE20JMRA5", s"Not a group, but a ${classNameOf(x)}")
      case None => throwNotFound("TyE02HMRG", s"No member with id $groupId")
    }
  }


  def getTheGroup(groupId: UserId): Group = {
    getGroup(groupId).getOrElse(throw UserNotFoundException(groupId))
  }


  def getGroup(groupId: UserId): Option[Group] = {
    COULD // instead lookup all groups, using: allGroupsKey, insert into the cache,  [LDALGRPS]
    // and then return. Instead of loading just one group?
    require(groupId >= Participant.LowestMemberId, "EsE4GKX24")
    getParticipant(groupId) map {
      case _: User => throw GotANotGroupException(groupId)
      case g: Group => g
      case _: Guest => die("TyE2AKBP068")
    }
  }


  def getParticipantOrUnknown(userId: UserId): Participant = {
    if (userId == UnknownParticipant.id) UnknownParticipant
    getParticipant(userId) getOrElse UnknownParticipant
  }


  def getTheParticipant(userId: UserId, anyTx: Opt[SiteTx] = None): Participant = {
    getParticipant(userId, anyTx).getOrElse(throw UserNotFoundException(userId))
  }


  def getParticipant(userId: UserId, anyTx: Opt[SiteTx] = None): Opt[Pat] = {
    anyTx foreach { tx =>
      return tx.loadParticipant(userId)
    }
    memCache.lookup[Participant](
      patKey(userId),
      orCacheAndReturn = {
        readOnlyTransaction { tx =>
          tx.loadParticipant(userId)
        }
      },
      ignoreSiteCacheVersion = true)
  }


  RENAME // to getPatBySessionId
  /**
    * Loads a user from the database.
    * Verifies that the loaded id match the id encoded in the session identifier,
    * and throws NO *returns* a LoginNotFoundException on mismatch (happens e.g. if
    * I've connected the server to another backend, or access many backends
    * via the same hostname but different ports).
    */
  def getUserBySessionId(sid: SidStatus): Option[Participant] Or LoginNotFoundException = {
    Good(sid.userId map { sidUserId =>
      val user = getParticipant(sidUserId) getOrElse {
        // This might happen 1) if the server connected to a new database
        // (e.g. a standby where the login entry hasn't yet been
        // created), or 2) during testing, when I sometimes manually
        // delete stuff from the database (including login entries).
        logger.warn(s"Didn't find user $siteId:$sidUserId [EdE01521U35]")
        return Bad(LoginNotFoundException(siteId, sidUserId))
      }
      if (user.id != sidUserId) {
        // Sometimes I access different databases via different ports,
        // but from the same host name. Browsers, however, usually ignore
        // port numbers when sending cookies. So they sometimes send
        // the wrong login-id and user-id to the server.
        logger.warn(s"DAO loaded the wrong user, session: $sid, user: $user [EdE0KDBL4]")
        return Bad(LoginNotFoundException(siteId, sidUserId))
      }
      user
    })
  }


  def loadMemberByPrimaryEmailAddress(emailAddress: String): Option[User] = {
    if (!emailAddress.contains("@"))
      return None
    loadMemberByEmailOrUsername(emailAddress)
  }

  def loadMemberByEmailOrUsername(emailOrUsername: String): Option[User] = {  // RENAME to ... PrimaryEmailAddress... ?
    readOnlyTransaction { tx =>
      // Don't need to cache this? Only called when logging in.
      tx.loadUserByPrimaryEmailOrUsername(emailOrUsername)
    }
  }


  def getMembersByUsernames(usernames: Iterable[Username]): ImmSeq[Opt[Member]] = {
    usernames.to(Vec).map(getMemberByUsername)
  }


  def getMemberByUsername(username: String): Option[Member] = {
    // The username shouldn't include '@'. Also, if there's a '@', might be
    // an email addr not a username.
    if (username.contains('@')) {
      return None
    }
    COULD_OPTIMIZE // can in-mem cache
    loadMemberByEmailOrUsername(username)
  }


  def getMemberBySsoId(ssoId: String): Option[Participant] = {
    COULD_OPTIMIZE // can in-mem cache
    loadMemberInclDetailsBySsoId(ssoId).map(_.briefUser)
  }


  def getParticipantByExtId(extId: ExtId): Option[Participant] = {
    readOnlyTransaction(_.loadUserInclDetailsByExtId(extId)).map(_.briefUser)
  }


  def loadMemberInclDetailsBySsoId(ssoId: String): Option[UserInclDetails] = {
    readOnlyTransaction(_.loadUserInclDetailsBySsoId(ssoId))
  }


  def getGroupIdsOwnFirst(user: Option[Participant]): Vector[UserId] = {
    user.map(getOnesGroupIds) getOrElse Vector(Group.EveryoneId)
  }


  COULD_OPTIMIZE // Cache both Vec and Map(groups.map(g => g.id, g): _*)  [cache_groups_by_id]
  //
  def getAllGroups(): Vec[Group] = {
    BUG // risk: ?? Maybe shouldn't cache Group:s, but group ids ??  [LDALGRPS]
    // So cannot get different results if loading groups via  allGroupsKey, or via  pptKey
    memCache.lookup[Vector[Group]](
      allGroupsKey,
      orCacheAndReturn = {
        readOnlyTransaction { tx =>
          Some(tx.loadAllGroupsAsSeq())
        }
      }).get
  }


  // Later: make allGroups required, remove getAllGroups() from impl?
  def getGroupsReqrMaySee(requester: Pat, allGroups: Opt[Vec[Group]] = None): Vec[Group] = {
    val tooManyGroups = allGroups getOrElse getAllGroups()

    val requestersGroupIds = getOnesGroupIds(requester)
    // + groups one manages or is an adder or bouncer for [GRPMAN]

    val groups =
      if (requester.isStaff) tooManyGroups
      else tooManyGroups
        // Later: .filter(g => g.isVisibleFor(requester) || requestersGroupIds.contains(g.id))
        // but start with the names of all custom groups visible (although one might not
        // be allowed to see their members)

    groups
  }


  def getGroupsAndStatsReqrMaySee(requester: Participant): Vector[GroupAndStats] = {
    val groups = getGroupsReqrMaySee(requester)
    val groupsAndStats = groups map { group =>
      GroupAndStats(group, getGroupStatsIfReqrMaySee(group, requester))
    }
    groupsAndStats
  }


  private def getGroupStatsIfReqrMaySee(group: Group, requester: Participant): Option[GroupStats] = {
    // Hmm this counts not only users, but child groups too. [sub_groups]
    COULD_OPTIMIZE // Count or cache in the database instead.
    val members = listGroupMembersIfReqrMaySee(group.id, requester) getOrElse {
      return None
    }
    Some(GroupStats(numMembers = members.length))
  }


  /** Returns Some(the members), or, if one isn't allowed to know who they are, None.
    * Returns _users_only, at least for now. Groups can't be group members yes anyway.
    */
  def listGroupMembersIfReqrMaySee(groupId: UserId, requester: Participant)
        : Opt[ImmSeq[UserBr]] = {
    // For now, don't allow "anyone" to list almost all members in the whole forum, by looking
    // at built-in groups like Everyone or All Members.
    if (groupId == Group.EveryoneId)
      return None

    val group: Group = getTheGroupOrThrowClientError(groupId)
    val requestersGroupIds = getOnesGroupIds(requester)

    // Let everyone see who the staff members are. That's important so one knows whom
    // to trust about how the community functions?
    // Later: configurable count/view-members per group settings.  [list_membs_perm]
    // Later: or if is manager [GRPMAN]
    if (!requester.isStaff && !requestersGroupIds.contains(groupId) && !Group.isStaffGroupId(groupId))
      return None

    if (requester.isStaffOrCoreMember) {
      // Ok, may list members.
    }
    else if (group.isBuiltIn && requester.effectiveTrustLevel.isBelow(TrustLevel.TrustedMember)) {
      // For now, don't let not-yet-trusted members list all members in the whole forum
      // (by looking at the All Members group members).
      return None
    }
    else {
      // Ok.
    }

    // (This could be its own fn, but it's good to have checked that groupId is indeed
    // a group first (done above), so one cannot DoS-attack by caching None for int-max
    // integers that are not group ids.)
    val membersTooMany = memCache.lookup[Vec[User]](
          groupMembersKey(groupId),
          orCacheAndReturn = {
            readTx { tx =>
              // For now, _users_only.
              Some(tx.loadGroupMembers(groupId).flatMap(_.toUserOrNone))
            }
          }).get

    val membersMayList: ImmSeq[PatAndPrivPrefs] =
          authz.PrivPrefs.filterMayList(membersTooMany, requester, dao = this)

    val usersMayList = membersMayList.map(_.pat.toUserOrThrow)
    Some(usersMayList)
  }


  def addGroupMembers(groupId: UserId, memberIdsToAdd: Set[UserId], reqrId: ReqrId): Unit = {
    throwForbiddenIf(groupId < Participant.LowestAuthenticatedUserId,
      "TyE206JKDT2", "Cannot add members to built-in automatic groups")
    readWriteTransaction { tx =>
      throwForbiddenIf(memberIdsToAdd.contains(groupId),
        "TyEGR2SELF", s"Cannot make group $groupId a member of itself")

      val newMembers = tx.loadParticipants(memberIdsToAdd)
      newMembers.find(_.isGroup) foreach { group =>
        // Currently trust level groups are already nested in each other — but let's
        // wait with allowing nesting custom groups in each other. [sub_groups]
        // [ck_grp_ckl]
        throwForbidden("TyEGRINGR", s"Cannot add groups to groups. Is a group: ${group.nameParaId}")
      }

      newMembers.find(_.isGuestOrAnon) foreach { guest =>
        throwForbidden("TyEGSTINGR", s"Cannot add guests or anons to groups; this pat: ${
              guest.nameParaId} is a ${guest.accountType}.")
      }

      // & !system or sysbot

      val maxLimits = getMaxLimits(UseTx(tx))

      // For now. Don't let a group become too large.
      val oldMemberIds = tx.loadGroupMembers(groupId).map(_.id).toSet
      val allMemberIdsAfter = oldMemberIds ++ memberIdsToAdd
      val maxMembers = maxLimits.maxMembersPerCustomGroup
      throwForbiddenIf(allMemberIdsAfter.size > maxMembers,
        "TyE2MNYMBRS", s"Group $groupId would get more than $maxMembers members")

      // Don't allow adding someone to very many groups — that could be a DoS attack.
      val maxGroups = maxLimits.maxGroupsMemberCanJoin
      val anyMemberInManyGroups = newMembers.find(member => {
        if (oldMemberIds.contains(member.id)) false
        else {
          val membersCurrentGroupIds = tx.loadGroupIdsMemberIdFirst(member)
          membersCurrentGroupIds.length >= maxGroups
        }
      })
      anyMemberInManyGroups foreach { m =>
        throwForbidden("TyEMBRIN2MNY", s"Member ${m.nameParaId} is in $maxGroups groups already, " +
          "cannot add to more groups")
      }

      AUDIT_LOG
      tx.addGroupMembers(groupId, memberIdsToAdd)
    }
    // Might need to uncache all pages where the members' names occur — if an is-member-of-group
    // title will be shown, next to the members' names. [grp-mbr-title]

    uncacheOnesGroupIds(memberIdsToAdd)
    memCache.remove(groupMembersKey(groupId))
  }


  def removeGroupMembers(groupId: UserId, memberIdsToRemove: Set[UserId], reqrId: ReqrId): Unit = {
    throwForbiddenIf(groupId < Participant.LowestAuthenticatedUserId,
      "TyE8WKD2T0", "Cannot remove members from built-in automatic groups")
    readWriteTransaction { tx =>
      AUDIT_LOG
      tx.removeGroupMembers(groupId, memberIdsToRemove)
    }
    // Might need to uncache pages. [grp-mbr-title]

    uncacheOnesGroupIds(memberIdsToRemove)
    memCache.remove(groupMembersKey(groupId))
  }


  private def uncacheGroupsMemberLists(groupIds: Iterable[UserId]): Unit = {
    groupIds foreach { groupId =>
      memCache.remove(groupMembersKey(groupId))
    }
  }


  def uncacheBuiltInGroups(): Unit = {
    import Group._
    uncacheGroupsMemberLists(
      Vector(EveryoneId, AllMembersId, BasicMembersId, FullMembersId,
        TrustedMembersId, RegularMembersId, CoreMembersId,
        StaffId, ModeratorsId, AdminsId))
  }


  private def uncacheOnesGroupIds(memberIds: Iterable[UserId]): Unit = {
    memberIds foreach { memberId =>
      memCache.remove(onesGroupIdsKey(memberId))
    }
  }


  /** Places one's [own_id_bef_groups]! Annoying. CLEAN_UP: remove own-id-first? */
  def getOnesGroupIds(ppt: Participant): Vector[UserId] = {
    ppt match {
      case _: Guest | _: Anonym | UnknownParticipant => Vector(Group.EveryoneId)
      case _: Member =>
        memCache.lookup[Vector[UserId]](
          onesGroupIdsKey(ppt.id),
          orCacheAndReturn = {
            readOnlyTransaction { tx =>
              // [own_id_bef_groups] sometimes annoying. (Cached! Need to update all callers.)
              Some(tx.loadGroupIdsMemberIdFirst(ppt))
            }
          }).get
    }
  }


  def joinOrLeavePageIfAuth(pageId: PageId, join: Bo, who: Who): Opt[BareWatchbar] = {
    // (Later, maybe optionally allow anons (conf val). [anon_priv_msgs])
    if (who.isGuestOrAnon)
      throwForbidden("EsE3GBS5", "Guest and anonymous users cannot join/leave pages")

    val joinOrLeave = if (join) Join else Leave

    val databaseResult = _joinLeavePage_updateDb_ifAuZ(Set(who.id), pageId,
          joinOrLeave, who, anyTx = None)

    if (!databaseResult.anyChange)
      return None

    val watchbarsByUserId = _joinLeavePage_updateWatchbar(
          userIds = Set(who.id), couldntAdd = databaseResult.patIdsCouldntJoin,
          joinOrLeave, pageToJoinLeave = databaseResult.pageMeta)

    val anyNewWatchbar = watchbarsByUserId.get(who.id)
    anyNewWatchbar
  }


  /** When a member joins the site, chat channels that are pinned globally get
    * added to hens watchbar. Also, when hen transitions to a higher trust level,
    * then more globally-pinned-chats might become visible to hen
    * (e.g. visible only to Trusted users) — and then those are added to the
    * watchbar, those too.
    */
  private def _addPinnedGlobalChatsToWatchbar(user: UserBase): U = {
    // Tests:  promote-demote-by-staff-join-leave-chats.2br  TyTE2E5H3GFRVK

    // Dupl code, also done when creating a watchbar. [auto_join_chats]
    val chatsInclForbidden = readTx(_.loadOpenChatsPinnedGlobally())
    BUG // Don't join a chat again, if has left it. Needn't fix now, barely matters.
    val joinedChats = chatsInclForbidden // ArrayBuffer[PageMeta]()

    /* Skip this — just add the chat to the watchbar instead.
    chatsInclForbidden foreach { chatPageMeta =>
      val maySeeResult = maySeePageUseCache(chatPageMeta, Some(user.noDetails))
      if (maySeeResult) {
        val couldntAdd = mutable.Set[UserId]()

        _joinLeavePageOrThrow(Set(user.id), chatPageMeta.pageId, add = true,
            byWho = Who.System, couldntAdd, tx)

        if (!couldntAdd.contains(user.id)) {
          joinedChats += chatPageMeta
        }
      }
    } */

    // This will do access permission checks.
    val userAuthzCtx = getAuthzCtxOnPagesForPat(user)
    _addRemovePagesToWatchbar(joinedChats, userAuthzCtx, Add)
  }


  private def _leavePagesMayNotSee_updateDb(user: UserVb)(
          tx: SiteTx, staleStuff: StaleStuff): ImmSeq[PageMeta] = {
    // Remove [chat channels pat may no longer see] from hens watchbar, otherwise hen
    // will get confused if clicking them and getting access permission errors.

    SHOULD // Remove pat from [private_chats] too, once private chats have been implemented.
    // Hmm but they won't work like that? They aren't tied to trust levels and category
    // permissions, instead, created outside any cat and a few pats added explicitly.

    // Tests:  promote-demote-by-staff-join-leave-chats.2br  TyTE2E5H3GFRVK.TyTE2ELWRTRU38
    UX; COULD // remove also non-pinned open chats pat may no longer see.
    // [demoted_rm_all_chats]

    val chatsInclForbidden = tx.loadOpenChatsPinnedGlobally()
    val chatsPatLeft = chatsInclForbidden flatMap { page =>
      val result = _joinLeavePage_updateDb_ifAuZ(Set(user.id), page.pageId,
            StayIfMaySee, Who.System, Some((tx, staleStuff)))
      if (result.anyChange) Some(result.pageMeta)
      else None
    }
    chatsPatLeft
  }


  def addUsersToPageIfAuZ(userIds: Set[UserId], pageId: PageId, byWho: Who): U = {
    val result = _joinLeavePage_updateDb_ifAuZ(userIds, pageId, Add, byWho, anyTx = None)
    if (result.anyChange) {
      dieIf(result.patIdsCouldntJoin.size >= userIds.size, "TyE603MRDL",
            "No user added, but still anyChange is true")
      _joinLeavePage_updateWatchbar(
            userIds = userIds, couldntAdd = Set.empty, Add, pageToJoinLeave = result.pageMeta)
    }
  }


  def removeUsersFromPageIfAuZ(userIds: Set[UserId], pageId: PageId, byWho: Who): U = {
    val result = _joinLeavePage_updateDb_ifAuZ(userIds, pageId, Remove, byWho, anyTx = None)
    if (result.anyChange) {
      _joinLeavePage_updateWatchbar(
            userIds = userIds, couldntAdd = Set.empty, Remove, pageToJoinLeave = result.pageMeta)
    }
  }


  private def _joinLeavePage_updateDb_ifAuZ(userIds: Set[UserId], pageId: PageId,
          joinOrLeave: JoinOrLeave, byWho: Who, anyTx: Opt[(SiteTx, StaleStuff)])  // REFACTOR use TxCtx
          : JoinLeavePageDbResult = {

    // (Later, maybe allow anons (conf val). [anon_priv_msgs])
    if (byWho.isGuestOrAnon)
      throwForbidden("EsE2GK7S", "Guests and anons cannot add/remove people to pages")

    if (userIds.size > 50)
      throwForbidden("EsE5DKTW02", "Cannot add/remove more than 50 people at a time")

    if (userIds.exists(Participant.isGuestId) && joinOrLeave == Join)
      throwForbidden("EsE5PKW1", "Cannot add guests to a page")

    val couldntAdd = mutable.Set[UserId]()

    COULD_OPTIMIZE  // return pats from _joinLeavePageOrThrow(), or load here.
    // So won't need to load again, in PinnedGlobaladdRemovePagesToWatchbar() [.2x_load_memb]

    val (pageMeta, anyChange) = writeTxTryReuse(anyTx) { (tx, _) =>
      // This checks if byWho may see the page, and may add userIds to it.
      _joinLeavePageOrThrow(userIds, pageId, joinOrLeave, byWho, couldntAdd, tx)
    }

    UX; SHOULD // push new member notf to browsers, so that this gets updated:
    // - new members' watchbars
    // - everyone's context bar (the users list)
    // - the Join Chat button (disappears/appears)

    // Chat page JSON includes a list of all page members, so:
    if (pageMeta.pageType.isChat) {
      refreshPageInMemCache(pageId)
    }

    JoinLeavePageDbResult(patIdsCouldntJoin = couldntAdd.toSet, pageMeta, anyChange = anyChange)
  }


  private def _joinLeavePage_updateWatchbar(userIds: Set[UserId], couldntAdd: Set[UserId],
        joinOrLeave: JoinOrLeave, pageToJoinLeave: PageMeta): Map[UserId, BareWatchbar] = {

    var watchbarsByUserId = Map[UserId, BareWatchbar]()
    userIds foreach { userId =>
      if (couldntAdd.contains(userId)) {
        // Need not update the watchbar.
      }
      else {
        COULD_OPTIMIZE  // needn't do access control again in _addRemovePagesToWatchbar(),
        // already done above, but results currently forgotten — pass back from
        // _joinLeavePageOrThrow() to here?

        COULD_OPTIMIZE  // Pass pat from _joinLeavePage_updateDb_ifAuZ() to here [.2x_load_memb]
        val pat = getTheUser(userId, anyTx = None)
        val patAuthzCtx = getAuthzCtxOnPagesForPat(pat)
        _addRemovePagesToWatchbar(Some(pageToJoinLeave), patAuthzCtx, addOrRemove = joinOrLeave
              ) foreach { newWatchbar =>
          watchbarsByUserId += userId -> newWatchbar
        }
      }
    }
    watchbarsByUserId
  }


  /** Returns:  (PageMeta, anyChange: Bo)
    */
  private def _joinLeavePageOrThrow(
        userIdsToJoinLeave: Set[UserId], pageIdToJoinLeave: PageId,
        joinOrLeave: JoinOrLeave, byWho: Who, couldntAdd: mutable.Set[UserId],
        tx: SiteTx): (PageMeta, Bo) = {

    val userIds = userIdsToJoinLeave
    // Tests:  promote-demote-by-staff-join-leave-chats.2br  TyTE2E5H3GFRVK.TyT502RKTJF4
    //          — trying to join a page one may not see

    val add = joinOrLeave == Join
    val usersById = tx.loadUsersAsMap(userIds + byWho.id)
    val reqer = usersById.getOrElse(byWho.id, throwForbidden(
          "EsE6KFE0X", s"s${siteId}: The requester cannot be found, id: ${byWho.id}"))

    val pageMeta = tx.loadPageMeta(pageIdToJoinLeave) getOrElse
          security.throwIndistinguishableNotFound("42PKD0")

    // AuthZ check 1/3:  May the *requester* see the page? (Hen might be sbd else than userIds.)
    throwIfMayNotSeePage(pageMeta, Some(reqer))(tx)  // [reqr_see_join_page]

    // Right now, to join a forum page =  [sub_communities], one just adds it to one's watchbar.
    // But we don't add/remove the user from the page members list, so nothing to do here.
    if (pageMeta.pageType == PageType.Forum)
      return (pageMeta, false)

    lazy val numMembersAlready = tx.loadMessageMembers(pageIdToJoinLeave).size
    if (add && numMembersAlready + userIds.size > 400) {
      // I guess something, not sure what?, would break if too many people join
      // the same page.
      throwForbidden("EsE4FK0Y2", o"""Sorry but currently more than 400 page members
            isn't allowed. There are $numMembersAlready page members already""")
    }

    val addingRemovingMyselfOnly = userIds.size == 1 && userIds.head == reqer.id

    // AuthZ check 2/3.
    // A mod can add/remove members to pages hen can see — and we've checked above
    // that reqer can see the page (in Authz check 1).
    //
    // Later: Sometimes / most-of-the-time? each member in a private chat
    // should be able to add more people to join that private chat?
    //
    if (!reqer.isStaff && reqer.id != pageMeta.authorId && !addingRemovingMyselfOnly)
      throwForbidden(
        "EsE28PDW9", "Only staff and the page author may add/remove people to/from the page")

    var anyChange = false

    if (add) {
      if (!pageMeta.pageType.isGroupTalk)
        throwForbidden("EsE8TBP0", s"Cannot add people to pages of type ${pageMeta.pageType}")

      userIds.find(!usersById.contains(_)) foreach { missingUserId =>
        throwForbidden("EsE5PKS40", s"User not found, id: $missingUserId, cannot add to page")
      }

      userIds foreach { id =>
        COULD_OPTIMIZE // batch insert all users at once (would slightly speed up imports)
        val wasAdded = tx.insertMessageMember(
              pageIdToJoinLeave, userId = id, addedById = reqer.id)
        if (wasAdded) {
          anyChange = true
        }
        else {
          // Someone else has added that user already. Could happen e.g. if someone
          // adds you to a chat channel, and you try to join it yourself at the same time.
          couldntAdd += id
        }
      }
    }
    else {
      // Remove all users from the page, or only those who may not see the page.
      val userIdsToRemove =
            if (joinOrLeave == Leave) userIds
            else if (joinOrLeave == StayIfMaySee) userIds.filter { userId =>
              if (userId == reqer.id) {
                // we've checked above already, in AuthZ check 1, that
                // the requester may see the page.
                false // don't incl in userIdsToRemove
              }
              else {
                // AuthZ check 3/3.
                COULD // Instead of getOrDie, if not found, can we just remove hen from
                // the page? And log a bug warning maybe.
                val user = usersById.getOrDie(userId, "TyE305MRKD24")
                !maySeePage(pageMeta, Some(user), UseTx(tx)).maySee
              }
            }
            else {
              die("TyE0MRG603MR", s"Weird joinOrLeave: $joinOrLeave")
            }
      userIdsToRemove foreach { id: UserId =>
        tx.removePageMember(pageIdToJoinLeave, userId = id, removedById = byWho.id)
      }
      anyChange = userIdsToRemove.nonEmpty
    }

    // Bump the page version, so the cached page json will be regenerated, now including
    // this new page member.
    // COULD add a numMembers field, and show # members, instead of # comments,
    // for chat topics, in the forum topic list? (because # comments in a chat channel is
    // rather pointless, instead, # new comments per time unit matters more, but then it's
    // simpler to instead show # users?)
    var pageMetaAfter = pageMeta
    if (anyChange) {
      pageMetaAfter = pageMeta.copyWithNewVersion
      tx.updatePageMeta(pageMetaAfter, oldMeta = pageMeta, markSectionPageStale = false)
    }

    (pageMetaAfter, anyChange)
  }


  private def _addRemovePagesToWatchbar(pages: Iterable[PageMeta],
        patAuthzCtx: AuthzCtxOnAllWithReqer, addOrRemove: AddOrRemove): Opt[BareWatchbar] = {
    RACE // when loading & saving the watchbar. E.g. if a user joins
    // a page henself, and another member adds hen to the page,
    // or another page, at the same time. Then, possibly the lost update bug
    // — harmless, just the watchbar.

    val oldWatchbar = getOrCreateWatchbar(patAuthzCtx)
    var newWatchbar = oldWatchbar
    for (page: PageMeta <- pages) {
      val maySeeResult = maySeePageUseCacheAndAuthzCtx(page, patAuthzCtx)
      val maySee = maySeeResult.maySee
      if (addOrRemove == Remove) {
        newWatchbar = newWatchbar.removePage(page, tryKeepInRecent = maySee)
      }
      else {
        // Add the page if pat may see it, else remove it.
        // Check (double check?) if the user may access the pages. [WATCHSEC]
        SEC_TESTS_MISSING // TyT602KRGJG  add page one may not see
        if (maySee) {
          if (addOrRemove == Add) {
            newWatchbar = newWatchbar.addPage(page, hasSeenIt = true)
          }
          else {
            // Don't add it — we're just removing things pat may not see.
          }
        }
        else {
          newWatchbar = newWatchbar.removePage(page, tryKeepInRecent = false)
        }
      }
    }

    _saveWatchbar_updateWatchedPages(
          oldWatchbar, newWatchbar = newWatchbar, patAuthzCtx.theReqer.id)
  }


  def removeFromWatchbarRecent(pageIds: Iterable[PageId], authzCtx: AuthzCtxWithReqer)
          : Opt[BareWatchbar] = {
    TESTS_MISSING
    getAnyWatchbar(authzCtx.theReqer.id) flatMap { oldWatchbar =>
      var newWatchbar = oldWatchbar
      for (pageId <- pageIds) {
        newWatchbar = newWatchbar.removeFromRecent(pageId)
      }
      _saveWatchbar_updateWatchedPages(
            oldWatchbar, newWatchbar = newWatchbar, authzCtx.theReqer.id)
    }
  }


  private def _saveWatchbar_updateWatchedPages(oldWatchbar: BareWatchbar,
          newWatchbar: BareWatchbar, userId: UserId): Opt[BareWatchbar] = {

    if (oldWatchbar == newWatchbar) {
      // This happens if we're adding a user whose in-memory watchbar got created in
      // `loadWatchbar` above — then the watchbar will likely be up-to-date already, here.
      return None
    }

    saveWatchbar(userId, newWatchbar)

    // If pages were added to the watchbar, we should start watching them. If we left
    // a private page, it'll disappear from the watchbar — then we should stop watching it.
    if (oldWatchbar.watchedPageIds != newWatchbar.watchedPageIds) {
      BUG // This sends a Presence.Active about the user — sometimes hen isn't. [presence_bug]
      // Move the Presence.Active part of this, to higher up the stack?
      pubSub.userWatchesPages(siteId, userId, newWatchbar.watchedPageIds)
    }

    Some(newWatchbar)
  }


  /** Promotes a new member to trust levels Basic and Normal member, if hen meets those
    * requirements, after the additional time spent reading has been considered.
    * (Won't promote to trust level Helper and above though.)
    *
    * And clear any notifications about posts hen has now seen.
    */
  def trackReadingProgressClearNotfsPerhapsPromote(
        user: Participant, pageId: PageId, postIdsSeen: Set[PostId], newProgress: PageReadingProgress)
        : ReadMoreResult = {
    // Tracking guests' reading progress would take a bit much disk space, makes disk-space DoS
    // attacks too simple. [8PLKW46]
    require(user.isMember, "EdE8KFUW2")

    // Don't track system, superadmins, deleted users — they aren't real members.
    if (user.id < LowestTalkToMemberId)
      return ReadMoreResult(0)

    var promotedUser: Opt[UserVb] = None
    var numMoreNotfsSeen = 0

    COULD_OPTIMIZE // use Dao instead, so won't touch db. Also: (5ABKR20L)
    readWriteTransaction { tx =>
      val pageMeta = tx.loadPageMeta(pageId) getOrElse {
        throwNotFound("TyETRCK0PAGE", s"No page with id '$pageId'")
      }

      if (newProgress.maxPostNr + 1 > pageMeta.numPostsTotal) // post nrs start on TitleNr = 0 so add + 1
        throwForbidden("EdE7UKW25_", o"""Got post nr ${newProgress.maxPostNr} but there are only
          ${pageMeta.numPostsTotal} posts on page '$pageId'""")

      val oldProgress = tx.loadReadProgress(userId = user.id, pageId = pageId)

      val (numMoreNonOrigPostsRead, numMoreTopicsEntered, resultingProgress) =
        oldProgress match {
          case None =>
            (newProgress.numNonOrigPostsRead, 1, newProgress)
          case Some(old) =>
            val numNewLowPosts =
              (newProgress.lowPostNrsRead -- old.lowPostNrsRead - PageParts.BodyNr).size
            // This might re-count a post that we re-reads...
            var numNewHighPosts =
              (newProgress.lastPostNrsReadRecentFirst.toSet -- old.lastPostNrsReadRecentFirst).size
            // ... but here we cap at the total size of the topic.
            val requestedNumNewPosts = numNewLowPosts + numNewHighPosts
            val maxNumNewPosts =
              math.max(0, pageMeta.numRepliesTotal - old.numNonOrigPostsRead) // [7FF02A3R]
            val allowedNumNewPosts = math.min(maxNumNewPosts, requestedNumNewPosts)
            (allowedNumNewPosts, 0, old.addMore(newProgress))
        }

      val (numMoreDiscourseRepliesRead, numMoreDiscourseTopicsEntered,
          numMoreChatMessagesRead, numMoreChatTopicsEntered) =
        if (pageMeta.pageType.isChat)
          (0, 0, numMoreNonOrigPostsRead, numMoreTopicsEntered)
        else
          (numMoreNonOrigPostsRead, numMoreTopicsEntered, 0, 0)

      val statsBefore = tx.loadUserStats(user.id) getOrDie "EdE2FPJR9"
      val statsAfter = statsBefore.addMoreStats(UserStats(
        userId = user.id,
        lastSeenAt = newProgress.lastVisitedAt,
        numSecondsReading = newProgress.secondsReading,
        numDiscourseTopicsEntered = numMoreDiscourseTopicsEntered,
        numDiscourseRepliesRead = numMoreDiscourseRepliesRead,
        numChatTopicsEntered = numMoreChatTopicsEntered,
        numChatMessagesRead = numMoreChatMessagesRead))

      COULD_OPTIMIZE // aggregate the reading progress in Redis instead. Save every 5? 10? minutes,
      // so won't write to the db so very often.  (5ABKR20L)

      numMoreNotfsSeen = tx.markNotfsForPostIdsAsSeen(
            user.id, postIdsSeen,
            skipEmails = user.emailNotfPrefs != EmailNotfPrefs.ReceiveAlways)

      tx.upsertReadProgress(userId = user.id, pageId = pageId, resultingProgress)
      tx.upsertUserStats(statsAfter)

      if (user.canPromoteToBasicMember) {
        if (statsAfter.meetsBasicMemberRequirements) {
          promotedUser = _promoteUser(user.id, TrustLevel.BasicMember, tx)
        }
      }
      else if (user.canPromoteToFullMember) {
        if (statsAfter.meetsFullMemberRequirements) {
          promotedUser =_promoteUser(user.id, TrustLevel.FullMember, tx)
        }
      }
      else {
        // Higher trust levels require running expensive queries; don't do that here.
        // Instead, will be done once a day in some background job.
      }
    }

    promotedUser foreach { user =>
      // Has now joined a higher trust level group.
      uncacheOnesGroupIds(Seq(user.id))
      uncacheBuiltInGroups()

      WOULD_OPTIMIZE // Only needed if hens watchbar alredy exists
      _addPinnedGlobalChatsToWatchbar(user)
    }

    ReadMoreResult(numMoreNotfsSeen = numMoreNotfsSeen)
  }


  def rememberVisit(user: Participant, lastVisitedAt: When): ReadMoreResult = {
    require(user.isMember, "TyEBZSR27") // see above [8PLKW46]
    if (user.id < LowestTalkToMemberId)
      return ReadMoreResult(0)
    readWriteTransaction { tx =>
      val statsBefore = tx.loadUserStats(user.id) getOrDie "EdE2FPJR9"
      val statsAfter = statsBefore.addMoreStats(UserStats(
        userId = user.id,
        lastSeenAt = lastVisitedAt))
      tx.upsertUserStats(statsAfter)
    }
    ReadMoreResult(numMoreNotfsSeen = 0)
  }


  def toggleTips(user: Pat, anyTipsSeen: Opt[TourTipsId], hide: Bo,
          onlyAnnouncements: Bo): U = {
    require(user.isMember, "TyE5AKR5J") // see above [8PLKW46]
    if (user.id < LowestTalkToMemberId)
      return ()
    writeTx { (tx, staleStuff) =>
      val dataBefore = tx.loadUserStats(user.id) getOrDie "TyEZ4KKJ5"
      val dataAfter =
            if (hide) {
              throwBadReqIf(onlyAnnouncements, "TyE03MSM57")
              throwBadReqIf(anyTipsSeen.isEmpty, "TyE03MSM58")
              dataBefore.addMoreStats(UserStats(
                    userId = user.id, tourTipsSeen = Some(anyTipsSeen.toVector)))
            }
            else {
              // Then, un-hide.
              throwBadReqIf(anyTipsSeen.isDefined,
                    "TyE760GEM35", "Cannot un-hide individual tips")
              if (onlyAnnouncements) {
                // Un-hide all server announcements, that is, remove all announcement
                // tips ids, /^SAn_/, from the tips-seen list.
                dataBefore.filterTipsSeen(tipsPrefix = "SAn_", keep = false)
              }
              else {
                // Un-hide everything except for announcements.
                dataBefore.filterTipsSeen(tipsPrefix = "SAn_", keep = true)
              }
            }
      tx.upsertUserStats(dataAfter)
      staleStuff.addPatDynData(user.id, memCacheOnly = true)
    }
  }


  /** Returns the user after (with new trust level) iff got a higher effective trust level
    * (taking into account if trust levela has been locked by staff).
    */
  private def _promoteUser(userId: UserId, newTrustLevel: TrustLevel, tx: SiteTx): Opt[UserVb] = {
    // If trust level locked, we'll promote the member anyway — but
    // member.effectiveTrustLevel won't change, because it considers the locked
    // trust level first.  If so, the [got more trust so can join more chats]
    // event won't happen until the trust level gets unlocked [join_opn_cht].
    val userBef = tx.loadTheUserInclDetails(userId)
    val userAft = userBef.copy(trustLevel = newTrustLevel)
    tx.updateUserInclDetails(userAft)
    TESTS_MISSING // Now new chat channels might be available  TyTE2E603RM8J
    val gotMoreTrust = userAft.effectiveTrustLevel.isAbove(userBef.effectiveTrustLevel)
    if (gotMoreTrust) Some(userAft)
    else None
  }


  def loadNotificationsSkipReviewTasks(userId: UserId, upToWhen: Option[When], me: Who,
        unseenFirst: Boolean = false, limit: Int = 100)
        : NotfsAndCounts = {
    val isAdmin = getParticipant(me.id).exists(_.isAdmin)
    readOnlyTransaction { tx =>
      if (me.id != userId) {
        if (!tx.loadParticipant(me.id).exists(_.isStaff))
          throwForbidden("EsE5Y5IKF0", "May not list other users' notifications")
      }
      SECURITY; SHOULD // filter out priv msg notf, unless isMe or isAdmin.
      debiki.JsonMaker.loadNotificationsToShowInMyMenu(userId, tx, unseenFirst = unseenFirst,
        limit = limit, skipDeleted = !isAdmin, upToWhen = None) // later: Some(upToWhenDate), and change to limit = 50 above?
    }
  }


  REFACTOR; CLEAN_UP // Delete, break out fn instead  [4KDPREU2]  because
  // doesn't need 2 different fns for verifying primary addr, and additional addrs?
  def verifyPrimaryEmailAddress(userId: UserId, verifiedAt: ju.Date): Unit = {
    // This is a new member henself verifying hens own email address.
    // We'll notify staff, if they now need to approve hens account.
    val emailsToSend = ArrayBuffer[Email]()

    writeTx { (tx, staleStuff) =>
      var user = tx.loadTheUserInclDetails(userId)
      user = user.copy(emailVerifiedAt = Some(verifiedAt))
      val userEmailAddress = user.primaryEmailInfo getOrDie "EdE4JKA2S"
      dieUnless(userEmailAddress.isVerified, "EdE7UNHR4")
      tx.updateUserInclDetails(user)
      tx.updateUserEmailAddress(userEmailAddress)
      // Now, when email verified, perhaps time to start sending summary emails.
      tx.reconsiderSendingSummaryEmailsTo(user.id)

      // Notify staff if this new member now is waiting for approval. TyTE2E502AHL4
      COULD // add individual prefs about these notfs.  [notf_schedule][snooze_schedule]
      // Need not notify *all* staff members — maybe just one or two. [nice_notfs]
      val settings = getWholeSiteSettings(Some(tx))
      if (settings.userMustBeApproved) {
        val emailTexts = talkyard.server.emails.out.Emails.inLanguage(settings.languageCode)
        val (site, siteOrigin, siteHostname) = theSiteOriginHostname(tx)
        val allAdmins = tx.loadAdmins()
        for {
          admin <- allAdmins
          // If an admin somehow hasn't approved hans email address.
          if admin.canReceiveEmail
          // When the owner verifies hans email, han will be in the allAdmins list
          // (and the only one in the list — the forum is empty, at that time).
          // Don't send han an an email about approving hanself:
          if admin.id != userId
        } {
          emailsToSend.append(Email.createGenId(
                EmailType.NewMemberToApprove,
                createdAt = tx.now,
                sendTo = admin.primaryEmailAddress,
                toUserId = Some(admin.id),
                subject = s"[$siteHostname] New member to approve",
                bodyHtml = emailTexts.newMemberToApproveEmail(
                      user, siteOrigin = siteOrigin)))
        }
      }

      staleStuff.addParticipantId(userId, memCacheOnly = true)
    }

    globals.sendEmails(emailsToSend, siteId)
  }


  SECURITY // Harmless right now, but should pass Who and authz.
  def setUserAvatar(userId: UserId, tinyAvatar: Option[UploadRef], smallAvatar: Option[UploadRef],
        mediumAvatar: Option[UploadRef], browserIdData: BrowserIdData): Unit = {
    require(smallAvatar.isDefined == tinyAvatar.isDefined, "EsE9PYM2")
    require(smallAvatar.isDefined == mediumAvatar.isDefined, "EsE8YFM2")
    readWriteTransaction { tx =>
      setUserAvatarImpl(userId, tinyAvatar = tinyAvatar,
        smallAvatar = smallAvatar, mediumAvatar = mediumAvatar, browserIdData, tx)
    }
  }


  private def setUserAvatarImpl(userId: UserId, tinyAvatar: Option[UploadRef],
        smallAvatar: Option[UploadRef], mediumAvatar: Option[UploadRef],
        browserIdData: BrowserIdData, tx: SiteTransaction): Unit = {

      val userBefore = tx.loadTheUserInclDetails(userId)  ; SECURITY ; COULD // loadTheUserOrThrowForbidden, else logs really long exception
      val userAfter = userBefore.copy(
        tinyAvatar = tinyAvatar,
        smallAvatar = smallAvatar,
        mediumAvatar = mediumAvatar)

      val hasNewAvatar =
        userBefore.tinyAvatar != userAfter.tinyAvatar ||
          userBefore.smallAvatar != userAfter.smallAvatar ||
          userBefore.mediumAvatar != userAfter.mediumAvatar

      val relevantRefs =
        if (!hasNewAvatar) Set.empty
        else
          userBefore.tinyAvatar.toSet ++ userBefore.smallAvatar.toSet ++
            userBefore.mediumAvatar.toSet ++ userAfter.tinyAvatar.toSet ++
            userAfter.smallAvatar.toSet ++ userAfter.mediumAvatar.toSet
      val refsInUseBefore = tx.filterUploadRefsInUse(relevantRefs)

      tx.updateUserInclDetails(userAfter)

      if (hasNewAvatar) {
        val refsInUseAfter = tx.filterUploadRefsInUse(relevantRefs)
        val refsAdded = refsInUseAfter -- refsInUseBefore
        val refsRemoved = refsInUseBefore -- refsInUseAfter
        refsAdded.foreach(tx.updateUploadQuotaUse(_, wasAdded = true))
        refsRemoved.foreach(tx.updateUploadQuotaUse(_, wasAdded = false))

        userBefore.tinyAvatar.foreach(tx.updateUploadedFileReferenceCount)
        userBefore.smallAvatar.foreach(tx.updateUploadedFileReferenceCount)
        userBefore.mediumAvatar.foreach(tx.updateUploadedFileReferenceCount)
        userAfter.tinyAvatar.foreach(tx.updateUploadedFileReferenceCount)
        userAfter.smallAvatar.foreach(tx.updateUploadedFileReferenceCount)
        userAfter.mediumAvatar.foreach(tx.updateUploadedFileReferenceCount)
        // A user's avatar is shown in posts written by him/her.
        tx.markPagesHtmlStaleIfVisiblePostsBy(userId)
      }
      removeUserFromMemCache(userId)

      // Clear the PageStuff cache (by clearing the whole in-mem cache), because
      // PageStuff includes avatar urls.
      SHOULD_OPTIMIZE // let above markPagesWithUserAvatarAsStale() return a page id list and
      // uncache only those pages.
      emptyCacheImpl(tx)  ; SHOULD_OPTIMIZE // use staleStuff.addPagesWithVisiblePostsBy() instead
  }


  def configRole(userId: RoleId,
        emailNotfPrefs: Option[EmailNotfPrefs] = None,
        activitySummaryEmailsIntervalMins: Option[Int] = None): Unit = {
    // Don't specify emailVerifiedAt — use verifyPrimaryEmailAddress() instead; it refreshes the cache.
    readWriteTransaction { tx =>
      var user = tx.loadTheUserInclDetails(userId)
      emailNotfPrefs foreach { prefs =>
        user = user.copy(emailNotfPrefs = prefs)
      }
      activitySummaryEmailsIntervalMins foreach { mins =>
        user = user.copy(summaryEmailIntervalMins = Some(mins))
      }
      tx.updateUserInclDetails(user)
    }
    removeUserFromMemCache(userId)
    // (No groups to update.)
  }


  def configIdtySimple(ctime: ju.Date, emailAddr: String, emailNotfPrefs: EmailNotfPrefs): Unit = {
    readWriteTransaction { tx =>
      tx.configIdtySimple(ctime = ctime,
        emailAddr = emailAddr, emailNotfPrefs = emailNotfPrefs)
      // COULD refresh guest in cache: new email prefs --> perhaps show "??" not "?" after name.
    }
  }


  def listUsersNotifiedAboutPost(postId: PostId): Set[UserId] =
    readOnlyTransaction(_.listUsersNotifiedAboutPost(postId))


  /** Returns all users on the page. No need to filter may-see-profile or anything,
    * because if you can see a page, you can also see all usernames participating
    * on that page.  Except for private comment authors. Or, could include authors
    * of private comments the requester may see [incl_priv_authors], but let's wait.
    */
  def listUsernamesOnPage(pageId: PageId): ImmSeq[UserBr] = {
    // See also: this.loadUserMayListByPrefix().
    // We exclude users who have posted private comments or bookmarks only, when
    // finding usernames, see: [dont_list_bookmarkers].
    readTx(tx => {
        COULD_OPTIMIZE // could cache, + maybe use 'limit'?
        tx.listUsernamesOnPage(pageId)
    })
  }


  def savePageNotfPrefIfAuZ(pageNotfPref: PageNotfPref, reqrTgt: ReqrAndTgt): U = {
    require(pageNotfPref.peopleId == reqrTgt.target.id, "TyE_PPL_NE_TGT_93MR2")
    _editMemberThrowUnlessSelfStaff(
          pageNotfPref.peopleId, reqrTgt.reqrToWho, "TyE2AS0574", "change notf prefs") {
            case c @ EditMemberCtx(tx, _, _, _) =>
      _authzNotfPref(pageNotfPref, reqrTgt)(tx)
      tx.upsertPageNotfPref(pageNotfPref)
    }
  }


  def deletePageNotfPrefIfAuZ(pageNotfPref: PageNotfPref, reqrTgt: ReqrAndTgt): Unit = {
    require(pageNotfPref.peopleId == reqrTgt.target.id, "TyE_PPL_NE_TGT_93MR3")
    _editMemberThrowUnlessSelfStaff(
          pageNotfPref.peopleId, reqrTgt.reqrToWho, "TyE5KP0GJL", "delete notf prefs") {
            case EditMemberCtx(tx, _, _, _) =>
      _authzNotfPref(pageNotfPref, reqrTgt,
            // It's ok for an admin to *remove* a notf pref, even if the target user
            // can no longer see the page or category.
            checkOnlyReqr = true)(tx)
      tx.deletePageNotfPref(pageNotfPref)
    }
  }


  /**  Checks if both the requester and the target user may see the page.
    */
  private def _authzNotfPref(notfPref: PageNotfPref, reqrTgt: ReqrAndTgt,
          checkOnlyReqr: Bo = true)(tx: SiteTx): U = {
    // Related test:
    //   - notf-prefs-private-groups.2browsers.test.ts  TyT406WMDKG26

    notfPref.pageId foreach { pageId =>
      throwIfMayNotSeePage2(pageId, reqrTgt, checkOnlyReqr = checkOnlyReqr)(Some(tx))
    }
    notfPref.pagesInCategoryId foreach { catId =>
      throwIfMayNotSeeCategory2(catId, reqrTgt, checkOnlyReqr = checkOnlyReqr)(Some(tx))
    }
  }


  def saveMemberPrivacyPrefsIfAuZ(forUserId: UserId, preferences: MemberPrivacyPrefs, byWho: Who)
          : MemberInclDetails = {
    _editMemberThrowUnlessSelfStaff(
          forUserId, byWho, "TyE4AKT2W", "edit privacy prefs") {
            case EditMemberCtx(tx, staleStuff, memberBefore, _) =>

      // Later: Could let only full members (or people who knows how the software works)
      // change their who-may-mention-or-message-me settings?  [tech_level]
      // For now, just hide that, client side, doesn't really matter anyway. And >= core members
      // can always mention everyone anyway.  [can_config_what_priv_prefs]
      /* Don't / wait with:
      throwForbiddenIff(!memberBefore.isStaffOrCoreMember,
            "TyEM0EDPRFS1", "May not edit these prefs")
      throwForbiddenIff(!memberBefore.isStaffOrCoreMember,
            "TyEM0EDPRFS2", "May not edit ...")  */

      val memberAfter = memberBefore.copyPrefs(privPrefs = preferences)
      tx.updateMemberInclDetails(memberAfter)

      staleStuff.addPatIds(Set(forUserId))
      if (memberAfter.isGroup) {
        // Priv prefs are inherited from groups. So need to refresh the cache, for changes
        // to groups to take effect.  [inherit_group_priv_prefs]
        staleStuff.addAllGroups()
      }
    }
  }


  def saveAboutMemberPrefsIfAuZ(preferences: AboutUserPrefs, byWho: Who): MemberInclDetails = {
    // Similar to saveAboutGroupPrefs below. (0QE15TW93)
    SECURITY // should create audit log entry. Should allow staff to change usernames.
    BUG // the lost update bug (if staff + user henself changes the user's prefs at the same time)

    val membAft = _editMemberThrowUnlessSelfStaff(
          preferences.userId, byWho, "TyE2WK7G4", "configure about prefs") {
            case EditMemberCtx(tx, staleStuff, _, me) =>

      val user = tx.loadTheUserInclDetails(preferences.userId)  // [7FKFA20]

      // Perhaps there's some security problem that would results in a non-trusted user
      // getting an email about each and every new post. So, for now:  [4WKAB02]
      SECURITY // (Later, do some security review, add more tests, and remove this restriction.)  <——
      //if (preferences.emailForEvery.exists(_.forEveryPost) && !user.isStaffOrMinTrustNotThreat(TrustLevel.TrustedMember))
      //  throwForbidden("EsE7YKF24", o"""Currently only trusted non-threat members may be notified about
      //    every new post""")

      if (user.fullName != preferences.fullName) {
        throwForbiddenIfBadFullName(preferences.fullName)
      }

      // Don't let people change their usernames too often.  Also see [ed_uname].
      if (user.username != preferences.username) {
        addUsernameUsageOrThrowClientError(
              user.id, newUsername = preferences.username, me = me, tx)
      }

      // Changing address is done via UserController.setPrimaryEmailAddresses instead, not here
      if (user.primaryEmailAddress != preferences.emailAddress)
        throwForbidden("DwE44ELK9", "Shouldn't modify one's email here")

      val userAfter = user.copyWithNewAboutPrefs(preferences)
      try tx.updateUserInclDetails(userAfter)
      catch {
        case _: DuplicateUsernameException =>
          throwForbidden("EdE2WK8Y4_", s"Username '${preferences.username}' already in use")
      }

      if (userAfter.summaryEmailIntervalMins != user.summaryEmailIntervalMins ||
        userAfter.summaryEmailIfActive != user.summaryEmailIfActive) {
        tx.reconsiderSendingSummaryEmailsTo(user.id)  // related: [5KRDUQ0]
      }

      staleStuff.addPatIds(Set(user.id))

      // Clear the page cache, if we changed the user's name.
      COULD_OPTIMIZE // Have above markPagesWithUserAvatarAsStale() return a page id list and
      // uncache only those pages / bump only page versions for the pages on which the user
      // has posted something that's been cached.
      // Or use staleStuff.markPagesWithUserAvatarAsStale() or addPagesWithVisiblePostsBy()?
      if (preferences.changesStuffIncludedEverywhere(user)) {
        staleStuff.addAllPages()
      }
    }

    membAft
  }


  def addUsernameUsageOrThrowClientError(memberId: UserId, newUsername: String,
          me: User, tx: SiteTransaction): Unit = {
        dieIf(Participant.isGuestId(memberId), "TyE04MSR245")
        throwForbiddenIf(Participant.isBuiltInParticipant(memberId),
          "TyE3HMSTUG563", "Cannot rename built-in members")
        throwForbiddenIfBadUsername(newUsername)

        val usersOldUsernames: Seq[UsernameUsage] = tx.loadUsersOldUsernames(memberId)

        // [CANONUN] load both exact & canonical username, any match —> not allowed
        // (unless one's own).
        val previousUsages = tx.loadUsernameUsages(newUsername)

        // For now: (later, could allow, if never mentioned, after a grace period. Docs [8KFUT20])
        val usagesByOthers = previousUsages.filter(_.userId != memberId)
        if (usagesByOthers.nonEmpty)
          throwForbidden("EdE5D0Y29_",
                o"""The username '$newUsername' is, or has been, in use by someone else.
                You cannot use it.""")

        // Sync with tests [max_uname_changes].
        val maxPerYearTotal = me.isStaff ? 20 | 12
        val maxPerYearDistinct = me.isStaff ? 10 | 4

        val recentUsernames = usersOldUsernames.filter(
            _.inUseFrom.daysBetween(tx.now) < 365)
        if (recentUsernames.length >= maxPerYearTotal)
          throwForbidden("DwE5FKW02",
            "You have changed the username too many times the past year")

        val recentDistinct = recentUsernames.map(_.usernameLowercase).toSet
        def yetAnotherNewName = !recentDistinct.contains(newUsername.toLowerCase)
        if (recentDistinct.size >= maxPerYearDistinct && yetAnotherNewName)
          throwForbidden("EdE7KP4ZZ_",
            "You have changed to different usernames too many times the past year")

        val anyUsernamesToStopUsingNow = usersOldUsernames.filter(_.inUseTo.isEmpty)
        anyUsernamesToStopUsingNow foreach { usage: UsernameUsage =>
          val usageStopped = usage.copy(inUseTo = Some(tx.now))
          tx.updateUsernameUsage(usageStopped)
        }

        tx.insertUsernameUsage(UsernameUsage(
          usernameLowercase = newUsername.toLowerCase, // [CANONUN]
          inUseFrom = tx.now,
          inUseTo = None,
          userId = memberId,
          firstMentionAt = None))
  }


  def saveAboutGroupPrefs(preferences: AboutGroupPrefs, byWho: Who): Unit = {
    // Similar to saveAboutMemberPrefs above. (0QE15TW93)
    SECURITY // should create audit log entry. Should allow staff to change usernames.
    BUG // the lost update bug (if staff + user henself changes the user's prefs at the same time)

    readWriteTransaction { tx =>
      val group = tx.loadTheGroupInclDetails(preferences.groupId)
      val me = tx.loadTheUser(byWho.id)
      require(me.isStaff, "EdE5LKWV0")
      dieIf(group.id == Group.AdminsId && !me.isAdmin, "TyE30HMTR24")

      val groupAfter = group.copyWithNewAboutPrefs(preferences)

      if (groupAfter.name != group.name) {
        throwForbiddenIfBadFullName(groupAfter.name)
      }

      if (groupAfter.theUsername != group.theUsername) {
        addUsernameUsageOrThrowClientError(group.id, groupAfter.theUsername, me = me, tx)
      }

      try tx.updateGroup(groupAfter)
      catch {
        case _: DuplicateUsernameException =>
          throwForbidden("EdED3ABLW2", s"Username '${groupAfter.theUsername}' already in use")
      }

      // If summary-email-settings were changed, hard to know which people were affected.
      // So let the summary-emails module reconsider all members at this site.
      if (groupAfter.summaryEmailIntervalMins != group.summaryEmailIntervalMins ||
          groupAfter.summaryEmailIfActive != group.summaryEmailIfActive) {
        tx.reconsiderSendingSummaryEmailsToEveryone()  // related: [5KRDUQ0] [8YQKSD10]
      }
    }

    removeUserFromMemCache(preferences.groupId)
    memCache.remove(allGroupsKey)
  }


  def savePatPerms(patId: PatId, perms: PatPerms, byWho: Who): U = {
    _editMemberThrowUnlessSelfStaff(patId, byWho, "TyE3ASHW6703", "edit pat perms",
                                      mustBeAdmin = true) {
          case EditMemberCtx(tx, staleStuff, memberInclDetails, _) =>
      // Permissions can only be configured on groups (not individual users).
      val groupBef: Group = memberInclDetails.asGroupOr(IfBadAbortReq)
      val groupAft = groupBef.copy(perms = perms)
      val validGroup = groupAft.checkValid(IfBadAbortReq)
      tx.updateGroup(validGroup)
      staleStuff.addPatIds(Set(patId))
      staleStuff.addAllGroups()
    }
  }


  def saveUiPrefs(memberId: UserId, prefs: JsObject, byWho: Who): Unit = {
    _editMemberThrowUnlessSelfStaff(memberId, byWho, "TyE3ASHWB67", "change UI prefs") {
          case EditMemberCtx(tx, staleStuff, memberInclDetails, _) =>
      tx.updateMemberInclDetails(memberInclDetails.copyPrefs(uiPrefs = Some(prefs)))
      staleStuff.addPatIds(Set(memberId))
    }
  }


  def loadMembersCatsTagsSiteNotfPrefs(member: Participant, anyTx: Option[SiteTransaction] = None)
        : Seq[PageNotfPref] = {
    readOnlyTransactionTryReuse(anyTx) { tx =>
      // Related code: [6RBRQ204]
      val ownIdAndGroupIds = tx.loadGroupIdsMemberIdFirst(member)
      val prefs = tx.loadNotfPrefsForMemberAboutCatsTagsSite(ownIdAndGroupIds)
      prefs
    }
  }


  /** Should do the same tests as [5LKKWA10].
    */
  private def throwForbiddenIfBadFullName(fullName: Option[String]): Unit = {
    throwForbiddenIf(Validation.checkName(fullName).isBad,
      "TyE5KKWDR1", s"Weird name, not allowed: $fullName")
  }


  /** Should do the same tests as [5LKKWA10].
    */
  private def throwForbiddenIfBadUsername(username: String): Unit = {
    throwForbiddenIf(Validation.checkUsername(username).isBad,
      "TyE4KUK02", s"Invalid username: $username")
    throwForbiddenIf(ReservedNames.isUsernameReserved(username),
      "TyE5K24ZQ1", s"Username is reserved: '$username'; pick another username")
  }


  def createGroup(username: String, fullName: Option[String], reqrId: ReqrId): Group Or ErrorMessage = {
    throwForbiddenIfBadFullName(fullName)
    throwForbiddenIfBadUsername(username)

    val result = readWriteTransaction { tx =>
      // Too many groups could be a DoS attack.
      val maxLimits = getMaxLimits(UseTx(tx))
      val currentGroups = tx.loadAllGroupsAsSeq()
      val maxCustomGroups = maxLimits.maxCustomGroups
      throwForbiddenIf(currentGroups.length >= maxCustomGroups + Group.NumBuiltInGroups,
        "TyE2MNYGRPS", s"Cannot create more than $maxCustomGroups custom groups")

      tx.loadUsernameUsages(username) foreach { usage =>
        return Bad(o"""There is, or was, already a member with username
          '$username', member id: ${usage.userId} [TyE204KMFG]""")
      }
      val groupId = tx.nextMemberId
      val group = Group(
        id = groupId,
        theUsername = username.toLowerCase,  // [CANONUN]
        name = fullName,
        createdAt = tx.now)
      tx.insertGroup(group)
      tx.insertUsernameUsage(UsernameUsage(
        usernameLowercase = group.theUsername,
        tx.now, userId = group.id))
      AUDIT_LOG // that reqrId created group
      Good(group)
    }
    memCache.remove(allGroupsKey)
    result
  }


  def deleteGroup(groupId: UserId, reqrId: ReqrId): Unit = {
    throwForbiddenIf(groupId < Participant.LowestAuthenticatedUserId,
      "TyE307DMAT2", "Cannot delete built-in groups")

    // If doesn't exist, fail with a client error instead of a server error inside the tx.
    getTheGroupOrThrowClientError(groupId)

    val formerMembers = readWriteTransaction { tx =>
      tx.loadTheGroupInclDetails(groupId) ; RACE // might fail (harmless)
      val members = tx.loadGroupMembers(groupId)
      tx.deleteUsernameUsagesForMemberId(groupId)
      tx.removeAllGroupParticipants(groupId)
      tx.deleteGroup(groupId)
      AUDIT_LOG // that reqrId deleted group
      members
    }
    RACE // not impossible that a member just loaded hens group ids from the database,
    // and inserts into the mem cache just after we've uncached, here?
    removeUserFromMemCache(groupId)
    uncacheOnesGroupIds(formerMembers.map(_.id))
    memCache.remove(groupMembersKey(groupId))
    memCache.remove(allGroupsKey)
  }


  def saveGuest(guestId: UserId, name: String): Unit = {
    // BUG: the lost update bug.
    readWriteTransaction { tx =>
      var guest = tx.loadTheGuest(guestId)
      guest = guest.copy(guestName = name)
      tx.updateGuest(guest)
    }
    removeUserFromMemCache(guestId)
  }


  def perhapsBlockRequest(request: play.api.mvc.RequestHeader, sidStatus: SidStatus,
        browserId: Option[BrowserId]): Unit = {
    if (request.method == "GET")
      return

    // Authenticated users are ignored here. Suspend them instead.
    if (sidStatus.userId.exists(Participant.isRoleId))
      return

    // Ignore not-logged-in people, unless they attempt to login as guests.
    if (sidStatus.userId.isEmpty) {
      val guestLoginPath = controllers.routes.LoginAsGuestController.loginGuest.url
      if (!request.path.contains(guestLoginPath))
        return
    }

    // COULD cache blocks, but not really needed since this is for post requests only.
    val blocks = loadBlocks(
      ip = request.remoteAddress,
      // If the cookie is new, that browser won't have been blocked yet.
      browserIdCookie = if (browserId.exists(_.isNew)) None else browserId.map(_.cookieValue))

    for (block <- blocks) {
      if (block.isActiveAt(globals.now()) && block.threatLevel == ThreatLevel.SevereThreat)
        throwForbidden("DwE403BK01", o"""Not allowed. Please sign up with a username
            and password, or login with Google or Facebook, for example.""")
    }
  }


  /** Returns the anonymized member.
    *
    * Tested here:
    * - EdT5WKBWQ2
    */
  def deleteUser(userId: UserId, byWho: Who): UserInclDetails = {
    val (user, usersGroupIds) = writeTx { (tx, staleStuff) =>
      tx.deferConstraints()

      val deleter = tx.loadTheParticipant(byWho.id)
      require(userId == deleter.id || deleter.isAdmin, "TyE7UKBW1")

      val anonUsername = "anon" + nextRandomLong().toString.take(10)
      val anonEmail = anonUsername + "@example.com"

      // Use this fn so uploads ref counts get decremented.
      setUserAvatarImpl(userId: UserId, tinyAvatar = None, smallAvatar = None, mediumAvatar = None,
        browserIdData = byWho.browserIdData, tx = tx)

      // Load member after having forgotten avatar images (above).
      val memberBefore = tx.loadTheUserInclDetails(userId)
      val groupIds = tx.loadGroupIdsMemberIdFirst2(memberBefore)

      throwForbiddenIf(memberBefore.isDeleted, "TyE0ALRDYDLD", "User already deleted")
      throwForbiddenIf(memberBefore.isGroup, "TyE0KWPP240", "Is a group")

      // This resets the not-mentioned-here fields to default values.
      val memberDeleted = UserInclDetails(
        id = memberBefore.id,
        // Reset the external id, so the external user will be able to sign up again. (Not our
        // choice to prevent that? That'd be the external login system's responsibility, right.)
        ssoId = None,
        fullName = None,
        username = anonUsername,
        createdAt = memberBefore.createdAt,
        isApproved = memberBefore.isApproved,
        reviewedAt = memberBefore.reviewedAt,
        reviewedById = memberBefore.reviewedById,
        primaryEmailAddress = anonEmail,
        emailNotfPrefs = EmailNotfPrefs.DontReceive,
        privPrefs = memberBefore.privPrefs,
        suspendedAt = memberBefore.suspendedAt,
        suspendedTill = memberBefore.suspendedTill,
        suspendedById = memberBefore.suspendedById,
        suspendedReason = memberBefore.suspendedReason,
        trustLevel = memberBefore.trustLevel,
        lockedTrustLevel = memberBefore.lockedTrustLevel,
        threatLevel = memberBefore.threatLevel,
        lockedThreatLevel = memberBefore.lockedThreatLevel,
        deactivatedAt = memberBefore.deactivatedAt,
        deletedAt = Some(tx.now))

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.DeleteUser,
        doerTrueId = byWho.trueId,
        doneAt = tx.now.toJavaDate,
        browserIdData = byWho.browserIdData,
        targetPatTrueId = Some(memberBefore.trueId2))

      // Right now, members must have email addresses. Later, don't require this, and
      // skip inserting any dummy email here. [no-email]
      tx.deleteAllUsersEmailAddresses(userId)
      tx.insertUserEmailAddress(UserEmailAddress(userId, anonEmail, addedAt = tx.now, verifiedAt = None))

      SECURITY; COULD // if the user needs to be blocked (e.g. a spammer), remember ... a hash?
      // of hens identity ids, in a block list, to prevent hen from signing up again.
      // Otherwise, right now, someone who signed up with Facebook and got blocked, can just
      // delete hens account and signup again with the same Facebook account.
      tx.deleteAllUsersIdentities(userId)

      // If we've sent emails to the user, delete hens email address from the emails.
      tx.forgetEmailSentToAddress(userId, replaceWithAddr = anonEmail)
      tx.forgetInviteEmailSentToAddress(userId, replaceWithAddr = anonEmail)

      // Audit log entries get scrubbed automatically after a while; don't delete them from here
      // (that'd be too soon — they're used to prevent e.g. app layer DoS attacks).

      // Keep usernames — to prevent others from impersonating this user.
      // And remember the current username, and the new anonNNNN username:
      PRIVACY // Maybe remember username hashes instead? hashed with sth like scrypt. [6UKBWTA2]
      // Can websearch for "privacy hash usernames".
      val oldUsernames: Seq[UsernameUsage] = tx.loadUsersOldUsernames(userId)
      oldUsernames.filter(_.inUseTo.isEmpty) foreach { usage: UsernameUsage =>
        val usageStopped = usage.copy(inUseTo = Some(tx.now))
        tx.updateUsernameUsage(usageStopped)
      }

      // The anonNNN name is canonical already, else change the code above.
      //dieIf(User.makeUsernameCanonical(anonUsername) != anonUsername, "TyE5WKB023")  [CANONUN]
      tx.insertUsernameUsage(UsernameUsage(
        anonUsername, inUseFrom = tx.now, userId = userId))

      tx.updateUserInclDetails(memberDeleted)
      tx.insertAuditLogEntry(auditLogEntry)

      tx.removeDeletedMemberFromAllPages(userId)
      tx.removeDeletedMemberFromAllGroups(userId)

      // logout(..) done below.  [end_sess]
      terminateSessions(forPatId = userId, all = true, anyTx = Some(tx, staleStuff))

      // Clear the page cache, by clearing all caches.
      emptyCacheImpl(tx)  ; SHOULD_OPTIMIZE // use staleStuff.addPagesWithVisiblePostsBy() instead

      (memberDeleted, groupIds)
    }

    // To uncache pages where the user's name appears: (now changed to anonNNN)
    memCache.clearThisSite()
    COULD // instead uncache only the pages on which hens name appears, plus this:
    // (this not needed, since cleared the site cache just above. Do anyway.)
    uncacheOnesGroupIds(Seq(userId))
    uncacheGroupsMemberLists(usersGroupIds)

    // Or just call:  logout(user, bumpLastSeen = false)
    pubSub.unsubscribeUser(siteId, user.briefUser)
    removeUserFromMemCache(userId)
    // terminateSessions() done above.

    user
  }


  def deleteGuest(guestId: UserId, byWho: Who): Guest = {
    ???  // don't forget to delete any ext imp id [03KRP5N2]
  }


  def getUsersOnlineStuff(): UsersOnlineStuff = {
    usersOnlineCache.get(siteId, new ju.function.Function[SiteId, UsersOnlineStuff] {
      override def apply(dummySiteId: SiteId): UsersOnlineStuff = {
        // Later: Exclude users with profiles hidden, but only   [priv_prof_0_presence]
        // for those who can't see their profiles. Might need to cache
        // one version of the users-online list per trust level group?
        // And exclude those with a may-not maySeeMyPresenceTrLv.
        val (userIdsInclSystem, numStrangers) = redisCache.loadOnlineUserIds()
        // If a superadmin is visiting the site (e.g. to help fixing a config error), don't  [EXCLSYS]
        // show hen in the online list — hen isn't a real member.
        val userIds = userIdsInclSystem.filterNot(Pat.isBuiltInPerson)
        // Load all this atomically? [user_version]
        val users = getUsersAsSeq(userIds)
        val tagsByUserId = getTagsByPatIds(userIds)
        val usersJson = users map { user =>
          val tags = tagsByUserId(user.id)
          JsX.JsUser(user, tags)
        }
        UsersOnlineStuff(
              users,
              tagsByUserId = tagsByUserId,
              numStrangers = numStrangers,
              cachedUsersJson = JsArray(usersJson))
      }
    })
  }


  /** Loads userId and byWho in a read-write transaction, and checks if they are
    * the same person (that is, one edits one's own settings) or if byWho is staff.
    * If isn't the same preson, or isn't staff, then, throws 403 Forbidden.
    * Plus, staff users who are moderators only, may not edit admins — that also
    * throws 403 Forbidden.
    *
    * @param block — EditMemberCtx(tx, staleStuff, member-to-edit, reqer) => side effects...  .
    */
  private def _editMemberThrowUnlessSelfStaff[R](userId: UserId, byWho: Who, errorCode: St,
        mayNotWhat: St, mustBeAdmin: Bo = false)(block: EditMemberCtx => U): MemberVb = {
    SECURITY // review all fns in UserDao, and in UserController, and use this helper fn?
    // Also create a helper fn:  readMemberThrowUnlessSelfStaff2 ...

    // There's a similar check in the Do-API:  [api_do_as],  maybe break out this fn
    // and use everywhere instead?  Maybe should use this fn also here:  [vote_as_otr]
    // or in fact whereever  ReqrAndTgt.areNotTheSame?

    throwForbiddenIf(byWho.id <= MaxGuestId,
      errorCode + "-MEGST", s"Guests may not $mayNotWhat")
    throwForbiddenIf(userId <= MaxGuestId,
      errorCode + "-ISGST", s"May not $mayNotWhat for guests")
    throwForbiddenIf(userId < Participant.LowestNormalMemberId,
      errorCode + "-ISBTI", s"May not $mayNotWhat for special built-in users")

    writeTx { (tx, staleStuff) =>
      val me = tx.loadTheUser(byWho.id)
      throwForbiddenIf(mustBeAdmin && !me.isAdmin, "TyE0ADM0536",
            s"Only admins may $mayNotWhat")
      // Split mods into "moderator" and "[power_mod]erator" trust levels — only
      // the latter will be able to do this:  (so current mods = power mods)
      throwForbiddenIf(me.id != userId && !me.isStaff,
          errorCode + "-ISOTR", s"May not $mayNotWhat for others")
      // [pps] load MemberInclDetails instead, and hand to the caller? (user or group incl details)
      // Would be more usable; sometimes loaded anyway [7FKFA20]
      val member = tx.loadTheMemberInclDetails(userId)
      throwForbiddenIf(member.isAdmin && !me.isAdmin,
          errorCode + "-ISADM", s"May not $mayNotWhat for admins") // [mod_0_conf_adm]

      block(EditMemberCtx(tx, staleStuff, member, reqer = me))

      tx.loadTheMemberInclDetails(userId)
    }
  }


  def removeUserFromMemCache(userId: UserId): Unit = {
    memCache.remove(patKey(userId))
  }

  def clearAllGroupsFromMemCache(): Unit = {
    memCache.remove(allGroupsKey)
    // (No need to clear group members though, that is, `groupMembersKey(..)`.)
  }

  private def patKey(userId: UserId) = MemCacheKey(siteId, s"$userId|PptById")

  // Which: 'u' = users only, 'g' = groups only, 'm' = members — both groups and users.
  private def membersByPrefixKey(prefix: String, which: String) =
    MemCacheKey(siteId, s"$prefix|$which|MbByPfx")

  private def allGroupsKey = MemCacheKey(siteId, "AlGrps")
  private def groupMembersKey(groupId: UserId) = MemCacheKey(siteId, s"$groupId|GrMbrs")

  // Not cached by patKey() — because is in a different table, and by using
  // different cache entries, we thereby eliminate some race conditions?
  // (It's confusing if seemingly unrelated changes (e.g. name and group memberships)
  // race against each other.)  [avoid_pat_cache_race]
  private def onesGroupIdsKey(userId: UserId) = MemCacheKey(siteId, s"$userId|GIdsByPp")

}

