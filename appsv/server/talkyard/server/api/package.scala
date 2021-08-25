package talkyard.server

import com.debiki.core._

package object api {

  sealed abstract class ApiTask

  // Later:  case class ApiQuery

  case class ApiAction(
    asWho: Pat,
    doWhat: ActionType,
    doWhy: Opt[St],
    doHow: ActionParams,
    // later:
    // doWhen: ..
    // doIf: ..
  ) extends ApiTask

  sealed abstract class ActionType

  object ActionType {
    case object SetVote extends ActionType
    case object SetNotfLevel extends ActionType

    def fromSt(st: St): Opt[ActionType] = Some(st match {
      case "SetVote" => SetVote
      case "SetNotfLevel" => SetNotfLevel
      case _ => return None
    })
  }

  sealed abstract class ActionParams

  case object NoActionParams extends ActionParams

  case class SetVoteParams(
        //whatVote: NotfLevel, — always Like vote
        whatPage: PageId) extends ActionParams

  case class SetNotfLevelParams(
        whatLevel: NotfLevel,
        whatPage: PageId) extends ActionParams

}
