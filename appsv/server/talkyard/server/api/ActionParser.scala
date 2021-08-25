package talkyard.server.api

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki.dao.SiteDao
import play.api.libs.json.{JsObject, JsValue, JsArray, Json}
import org.scalactic.{Bad, ErrorMessage, Good, Or}
import debiki.JsonUtils._

case class ActionParser(dao: SiteDao) {

  def parseAction(doWhatSt: St, actionJsOb: JsObject): ApiAction Or ErrMsg = {

    val actionType = ActionType.fromSt(doWhatSt) getOrElse {
      return Bad(s"Unknown action type: $doWhatSt")
    }

    // Could cache asWhoSt –> pat?  would be the same, for all actions?
    // (Unless is a 'username:__' ref, and changes one's username in the middle
    // of a series of actions.)
    val asWhoSt = parseSt(actionJsOb, "asWho")
    val anyPat: Opt[Pat] = dao.getParticipantByRef(asWhoSt) getOrIfBad { problem =>
      return Bad(s"Bad asWho: $problem")
    }
    val pat = anyPat getOrElse {
      return Bad(s"No such participant: $asWhoSt")
    }

    if (pat.isSystemUser)
      return Bad(o"""You cannot use the System user when doing things via Talkyard's API
              — but you can use Sysbot, or an ordinary member [TyEAPIUSRSYS]""")

    // For now
    if (pat.isBuiltIn)
      return Bad(o"""Currently built-in users cannot do things via
            the API [TyEAPIUSRGST]""")

    // Guests may not do lots of things.
    if (pat.isGuest) {
      actionType match {
        // Later, but first verify it's a Like vote:
        // case ActionType.SetVote =>
        //   // Fine, guests may Like vote.
        case x =>
          return Bad(s"Participant $asWhoSt is a guest and therefore may not: $doWhatSt")
      }
    }

    // Groups also may not do lots of things. For now:
    if (pat.isGroup)
      return Bad(o"""Currently groups cannot do things via the API [TyEAPIUSRGROUP]""")

    dieIf(!pat.isUserNotGuest, "TyE502MSE6")

    val doHowJsOb = parseJsObject(actionJsOb, "doHow")

    val whatPageJsOb = parseJsObject(doHowJsOb, "whatPage")
    val pageId = parseSt(whatPageJsOb, "pageId")

    val params = actionType match {
      case ActionType.SetVote =>
        SetVoteParams(whatPage = pageId)
      case ActionType.SetNotfLevel =>
        val whatLevelSt = parseSt(doHowJsOb, "whatLevel")
        val notfLevel = NotfLevel.fromSt_apiV0(whatLevelSt) getOrElse {
          return Bad(s"Unrecognized notification level: $whatLevelSt")
        }
        SetNotfLevelParams(whatLevel = notfLevel, whatPage = pageId)
    }

    Good(ApiAction(
        asWho = pat,
        doWhat = actionType,
        doWhy = parseOptSt(actionJsOb, "doWhy").trimNoneIfBlank,
        doHow = params,
        ))
  }

}
