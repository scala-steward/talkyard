/**
 * Copyright (c) 2016 Kaj Magnus Lindberg
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

import com.debiki.core.ThreatLevel.{MildThreat, SevereThreat}


sealed abstract class TrustLevel(val IntVal: Int) {
  def toInt: Int = IntVal

  def isBelow(other: TrustLevel): Bo =
    toInt < other.toInt

  def isAbove(other: TrustLevel): Bo =
    toInt > other.toInt

  def isAtMost(level: TrustLevel): Bo =
    toInt <= level.toInt

  def isAtLeast(level: TrustLevel): Bo =
    toInt >= level.toInt

  def isStrangerOrNewMember: Bo = IntVal <= TrustLevel.NewMember.IntVal
}


/** The same as Discourse's trust levels, plus one more level: the helpful member,
  *
  * Discourse's trust levels:
  * https://meta.discourse.org/t/what-do-user-trust-levels-do/4924/6
  *
  * About the additional trust level:
  * https://meta.discourse.org/t/a-new-trust-level-the-helpful-member/56894
  */
object TrustLevel {
  case object Stranger extends TrustLevel(0)   ; REFACTOR // bump all 1, so won't start at 0
  //se object Guest — same as Stranger, except for private communities that have invited a stranger
  //                  as a guest? Such an invited person would have trust level Guest and
  //                  could see "public" topics in the community although the community was private.
  case object NewMember extends TrustLevel(1)   // has created a real account
  case object BasicMember extends TrustLevel(2)
  case object FullMember extends TrustLevel(3)
  //se object BitTrustedMember or WellBehavedMember?  — the software promotes up to here only
  case object TrustedMember extends TrustLevel(4)
  case object RegularMember extends TrustLevel(5)   ; RENAME // to Trusted Regular, no, remove, and let visit frequency be another dimension, don't conflate with trust level
  case object CoreMember extends TrustLevel(6)

  // + Mod,
  // + ModOfMods (can resolve disagreements between mods)
  // + Admin,
  // + AdminOfAdmins — the site owner is *by default* AdminOfAdmins? (Other admins cannot depose hen)

  // But Owner is a flag?


  // Not real trust levels, but sometimes simpler to remember just one digit, say 7,
  // instead of 3 things: level + isStaff + isModerator.
  val StrangerDummyLevel = 0
  val ModeratorDummyLevel = 7
  val AdminDummyLevel = 8

  def fromInt(value: Int): Option[TrustLevel] = Some(value match {
    case TrustLevel.Stranger.IntVal => TrustLevel.Stranger
    case TrustLevel.NewMember.IntVal => TrustLevel.NewMember
    case TrustLevel.BasicMember.IntVal => TrustLevel.BasicMember
    case TrustLevel.FullMember.IntVal => TrustLevel.FullMember
    case TrustLevel.TrustedMember.IntVal => TrustLevel.TrustedMember
    case TrustLevel.RegularMember.IntVal => TrustLevel.RegularMember
    case TrustLevel.CoreMember.IntVal => TrustLevel.CoreMember
    case _ => return None
  })

  def fromBuiltInGroupId(groupId: Int): Option[TrustLevel] = Some(groupId match {
    case Group.EveryoneId => TrustLevel.Stranger
    case Group.AllMembersId => TrustLevel.NewMember
    case Group.BasicMembersId => TrustLevel.BasicMember
    case Group.FullMembersId => TrustLevel.FullMember
    case Group.TrustedMembersId => TrustLevel.TrustedMember
    case Group.RegularMembersId => TrustLevel.RegularMember
    case Group.CoreMembersId => TrustLevel.CoreMember
    case _ =>
      // Skip: Group.ModeratorsId, AdminsId, because StrangerDummyLevel and
      // moderators and admins aren't trust levels. [COREINCLSTAFF]
      // Staff members can have trust level just Basic or Core Member or whatever.
      return None
  })
}



/** How likely someone is to cause troubles, e.g. post toxic comments
  * or start a flame war.
  */
sealed abstract class ThreatLevel(val IntVal: Int) {
  def toInt: Int = IntVal
  def isSevereOrWorse: Boolean = toInt >= SevereThreat.toInt
  def isThreat: Boolean = toInt >= MildThreat.toInt
}


object ThreatLevel {

  case object SuperSafe extends ThreatLevel(1)

  case object SeemsSafe extends ThreatLevel(2)

  /** The default. */
  case object HopefullySafe extends ThreatLevel(3)

  /** All comments will be published directly, but also added to the moderation queue for review. */
  case object MildThreat extends ThreatLevel(4)

  /** Comments won't be published until they've been approved by a moderator.
    *
    * At most N comments (say, 5) may be pending review (if more, additional post
    * are rejected) — not impl though.
    *
    * Ought to automatically find these threat users: those whose posts
    * get classified as spam, by spam check services. And/or whose posts the
    * staff rejects and deletes. Partly impl, see [DETCTHR].
    */
  case object ModerateThreat extends ThreatLevel(5)

  /** May not post any comments at all. */
  case object SevereThreat extends ThreatLevel(6)

  def fromInt(value: Int): Option[ThreatLevel] = Some(value match {
    case ThreatLevel.HopefullySafe.IntVal => ThreatLevel.HopefullySafe
    case ThreatLevel.MildThreat.IntVal => ThreatLevel.MildThreat
    case ThreatLevel.ModerateThreat.IntVal => ThreatLevel.ModerateThreat
    case ThreatLevel.SevereThreat.IntVal => ThreatLevel.SevereThreat
    case _ => return None
  })
}

