package talkyard.server.api

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki.dao.SiteDao
import play.api.libs.json.{JsObject, JsValue, JsArray, Json}
import org.scalactic.{Bad, ErrorMessage, Good, Or}
import debiki.JsonUtils._

case class ActionDoer(dao: SiteDao) {

  def doAction(action: ApiAction): AnyProblem = {
    action.doWhat match {
      case ActionType.SetVote =>
        // Currently only for setting num Like votes to exactly 1.
      case ActionType.SetNotfLevel =>
        // Currently only for setting the notf level to EveryPost.
    }
    Fine
  }

}
