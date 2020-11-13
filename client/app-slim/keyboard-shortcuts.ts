/*
 * Copyright (c) 2020 Kaj Magnus Lindberg
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

/// <reference path="prelude.ts" />
/// <reference path="widgets.ts" />
/// <reference path="utils/react-utils.ts" />


/// Talkyard Shortcuts
///
/// The shortcuts are sequences of alphanumeric keys, lowercase only.
/// E.g. 'gaa' for "Go to Admin Area" or 'mn' for "view My Notifications".
///
/// When starting typing, all shortcuts matching the keys hit thus far,
/// are shown in a pop up dialog. This makes the shortcuts discoverable,
/// and no need to remember them by heart (except for maybe the first letter)
///
/// Shift opens the shortcuts dialog and shows all shortcuts
/// — this make the shortcuts even more discoverable? People sometimes
/// click Shift by accident. Then they'll discover Ty's shortcuts?
///
/// It needs to be simple to cancel any shortcut being typed.  Don't want
/// people getting stuck in "shortcut mode", like people can get stuck in Vim
/// or Ex.  Therefore, doing anything at all except for typing the next key
/// (or Backspace to undo the last key), cancels any ongoing shortcut sequence
/// and closes the dialog.
/// E.g. moving the mouse, closes the dialog (unless the pointer is inside the dialog).
/// Or hitting Space, Alt, Shift, Berserk Mode, Tab, whatever, or scrolling.
///
/// Ctrl or Shift are never parts of shortcuts.
/// Why not?
/// Using e.g. Ctrl+(a key) as a shortcut, would almost certainly? conflict
/// with *someone's* privately configured browser or OS shortcuts,
/// or built-in browser or OS shortcuts — which vary between operating
/// systems and browsers.
/// But by never using Ctrl+(a key) for shortcuts, we won't run into any collisions.
/// And Shift + a letter doesn't really save any keystrokes? Shift is a button too.
/// However, with Shift, people sometimes wouldn't easily know if say 'l' is
/// lowercase L or uppercase i, or if 'O' is uppercase o or 0 (zero)?
/// Better always use lowercase? (pat should notice everything is lowercase,
/// and thus know that 'l' is lowercase 'L' not 'I').
///


// MOVE to more-bundle ?   or keyboard-mouse-bundle  ?
// REMOVE  keymaster?
// ADD  Ctrl+Enter shortcut?   onCtrlEnter(() => Vo)  offCtrlEnter(...)
//                             onEsc(() => Vo)  offEsc(...)
//   onLeft onRight onUp onDown onEnter onTab onShiftTab   onDel
//
//   or   onX(fn),  onX(fn, false) to bind and unbind.
//
// Or not needed? The editor instead:
//
//    onKeyPress: this.onKeyPressOrKeyDown,
//    onKeyDown: this.onKeyPressOrKeyDown,
//    if (event_isCtrlEnter(event)) ...
//
// then no need to bind & unbind.
// So Keymaster only 1 2 3 4  w s,   Can remove now, right?


//------------------------------------------------------------------------------
   namespace debiki2.KeyboardShortcuts {
//------------------------------------------------------------------------------

const r = ReactDOMFactories;


type ShortcutFn = (store: Store) => Vo;
type ShortcFnInfoZ = ShorcFnItem | ShortcInfoItem | Z;


// (Not an object — nice to not have to read the same field names
// over and over again (arrays have no field names).)
type ShorcFnItem = [
  // Shortcut keys, e.g. "gaa".
  St,
  // Description, e.g. "Go to Admin Area".
  St | RElm,
  // The effect, what it does, e.g. () => { location.assign(...) }.
  // Index = 2 = ShortcutFnIx below.
  ShortcutFn];

const ShortcutFnIx = 2;


type ShortcInfoItem = [
  // When to show this info text, e.g. 'g' means if no key pressed, or 'g' pressed.
  St,
  // Info text. E.g. shows one's current page notf level, just above
  // the change notf level shortcuts.
  St | RElm]


interface DiagState {
  isOpen: Bo;
  tryStayOpen?: Bo;
  keysTyped: St;
  shortcutsToShow: ShortcFnInfoZ[];
}



function makeMyShortcuts(store: Store, keysTyped: St): ShortcFnInfoZ[] {
  const curPage: Page | U = store.currentPage;
  const curPageId: PageId | U = store.currentPageId;
  const curPageType: PageRole | U = curPage && curPage.pageRole;
  const curPageAuthorId: PatId | U =
          curPageId && store.currentPage.postsByNr[BodyNr]?.authorId;
  const isDisc = page_isDiscussion(curPageType);
  const isForum = curPageType === PageRole.Forum;

  const me: Me = store.me;
  const isAdmin: Bo = me.isAdmin;
  const isStaff: Bo = pat_isStaff(me);
  const isMember: Bo = me.isAuthenticated;
  const isPageAuthorOrStaff = isStaff || curPageAuthorId === me.id;

  // Later:  mayChangePage = ... calculate permissions, look at PostActions,
  // show Edit button or not, for example.

  const pageNotfsTarget: PageNotfPrefTarget | Z =
          curPageId && { pageId: curPageId };

  const effPref: EffPageNotfPref | Z =
          curPageId && isMember &&
            pageNotfPrefTarget_findEffPref(pageNotfsTarget, store, me);

  const notfLevel: PageNotfLevel | Z = effPref && notfPref_level(effPref);
  const notfLevelTitle: St | Z = effPref && notfPref_title(effPref)

  function changeNotfLevel(newLevel: PageNotfLevel): () => Vo {
    return function() {
      Server.savePageNotfPrefUpdStoreIfSelf(
            store.me.id, pageNotfsTarget as PageNotfPrefTarget, newLevel,
            sayWhatHappened(rFr({}, `Notification level is now: `, '')));
    }
  }

  function goTo(path: St): () => Vo {
    return function() {
      location.assign(path);
    }
  }

  return [   // I18N shortcut descriptions
      ['/', descr('', "search — open the search dialog"),
          function() { logM('SHORTCUT WOW /'); }],


      // ----- In a topic

      isPageAuthorOrStaff &&
      ['c',
          `Change:`
          ] as ShortcInfoItem,

      isPageAuthorOrStaff &&
      ['cca',
          descr('c', "hange topic ", 'ca', "tegory"),
          () => {}],

      isPageAuthorOrStaff &&
      ['cst',
          descr('c', "hange topic ", 'st', "atus, e.g. to Doing or Done"),
          () => {}],

      isPageAuthorOrStaff && page_isOpen(curPage) &&
      ['ccl',
          descr('c', "hange topic: ", 'cl', "ose it"),
          () => {}],

      isPageAuthorOrStaff && page_isClosedNotDone(curPage) &&
      ['cre',
          descr('c', "hange topic: ", 're', "open it"),
          () => {}],

      isMember && isDisc &&
      ['cn',
          `Your notification level for this topic is: ${notfLevelTitle}`
          ] as ShortcInfoItem,

      isMember && isDisc && notfLevel !== PageNotfLevel.EveryPost &&
      ['cne',
          descr('c', "hange ", 'n', "otification level to ", 'e', "very post"),
          changeNotfLevel(PageNotfLevel.EveryPost)],

      isMember && isDisc && notfLevel !== PageNotfLevel.Normal &&
      ['cnn',
          descr('c', "hange ", 'n', "otification level to ", 'n', "ormal"),
          changeNotfLevel(PageNotfLevel.Normal)],

      isMember && isDisc && notfLevel !== PageNotfLevel.Hushed &&
      ['cnh',
          descr('c', "hange ", 'n', "otification level to ", 'h', "ushed"),
          changeNotfLevel(PageNotfLevel.Hushed)],


      // ----- Forum topic index

      isMember && isForum &&
      ['cn',
          descr('c', "reate ", 'n', "ew topic"),
          () => {}],


      // ----- Reply

      isMember && isDisc &&
      ['r',
          `Reply:`
          ] as ShortcInfoItem,

      isMember && isDisc &&
      ['rr',
          descr('r', "eply to the cu", 'r', "rently focused post"),
          () => {}],

      isMember && isDisc &&
      ['ro',
          descr('r', "eply to the ", 'o', "riginal post"),
          () => {}],


      // ----- Edit

      isPageAuthorOrStaff &&
      ['e',
          `Edit:`
          ] as ShortcInfoItem,

      isPageAuthorOrStaff &&
      ['eti',
          descr('e', "dit topic", 'ti', "tle"),
          () => {}],

      isPageAuthorOrStaff &&
      ['ebo',
          descr('e', "dit topic", 'bo', "dy"),
          () => {}],


      // ----- My things

      isMember &&
      ['m',
          `My things:`
          ] as ShortcInfoItem,

      isMember &&
      ['mn', descr('', "view ", 'm', "y ", 'n', "otifications"),
          goTo(linkToUsersNotfs(store))],

      isMember &&
      ['md',
          descr('', "view ", 'm', "y ", 'd', "rafts"),
          goTo(linkToMyDraftsEtc(store))],


      // ----- Go

      ['g',
          `Go elsewhere:`
          ] as ShortcInfoItem,

      ['gb',
          descr('g', "o ", 'b', "ack to last page"),
          () => history.back()],

      ['gf',
          descr('g', "o ", 'f', "orward"),
          () => history.forward()],

      ['gr',
          descr('g', "o to ", 'r', "ecently active topics"),
          goTo('/latest')],  // for now

      ['gw',
          descr('g', "o to ", 'w', "aiting topics, e.g. an unanswered question"),
          goTo('/latest?filter=ShowWaiting')],  // for now

      ['gn',
          descr('g', "o to ", 'n', "ewest topics"),
          goTo('/new')],  // for now

      isStaff &&
      ['ggr',
          descr('g', "o to the ", 'gr', "oups list"),
          goTo(linkToGroups())],  // for now

      isStaff &&
      ['ggb',
          descr('g', "o to the ", 'gr', "oups list  PATH ONLY?"),
          goTo('/-/groups/')],  // for now

      isAdmin &&
      ['gaa',
          descr('g', "o to ", 'a', "dmin ", 'a', "rea"),
          goTo(linkToAdminPage())],

      isAdmin &&
      ['gau',
          descr('g', "o to ", 'a', "dmin area, the ", 'u', "sers tab"),
          goTo(linkToStaffUsersPage())],

      isStaff &&
      ['gmo',
          descr('g', "o to ", 'mo', "deration page"),
          goTo(linkToReviewPage())],
      ];
}



function descr(key1: St, text1: St, k2?: St, t2?: St, k3?: St, t3?: St): RElm {
  return rFr({},
      key1 ? r.b({}, key1) : null,
      text1,
      k2 ? r.b({}, k2) : null,
      t2,
      k3 ? r.b({}, k3) : null,
      t3,
      );
}



function sayWhatHappened(whatHappened: St | RElm): () => Vo {
  return function() {
    util.openDefaultStupidDialog({
      //dialogClassName: 's_UplErrD',
      closeButtonTitle: t.Okay,
      body: whatHappened,
    });
  }
}



function findMatchingShortcuts(shortcuts: ShortcFnInfoZ[],
          keysTyped: St): ShortcFnInfoZ[] {
  const schortcutsRightKeys = shortcuts.filter((keysDescrFn: ShortcFnInfoZ) => {
    if (!keysDescrFn) return false;
    const keys = keysDescrFn[0];
    return keysTyped.length <= keys.length && keys.startsWith(keysTyped);
  });
  
  return schortcutsRightKeys;
}



let skipNextShiftCtrlUp: Bo | U;
let shiftOrCtrlDown: Bo | U;
let curKeyDown = ''; // could be a key —> Bo map, but this'll do for now
let curState: DiagState = { isOpen: false, keysTyped: '', shortcutsToShow: [] };
let keepOpenMore: Bo = false;
let dialogSetState: (state: DiagState) => Vo | U;


export function start() {
  logD(`Starting shortcuts [TyMSHORTCSON]`);
  addEventListener('keydown', onKeyDown, false);
  addEventListener('keyup', onKeyUp, false);
  //addEventListener('focus', resetAndCloseDialog, false);
  //addEventListener('blur', resetAndCloseDialog, false);
  addEventListener('hashchange', resetAndCloseDialog, false);
  addEventListener('click', onMouseMaybeReset, false);
  addEventListener('mousemove', onMouseMaybeReset, false);
  addEventListener('scroll', resetAndCloseDialog, false);
  addEventListener('touchmove', resetAndCloseDialog, false);
  addEventListener('mousewheel', resetAndCloseDialog, false);
  // Never unregister.
}



/// We'll skip shortcuts, if any modal dialog is open.
function anyOtherDialogOpen(): Bo {
  return !curState.isOpen && !!$first('.modal');
}



function resetAndCloseDialog() {
  logD(`resetAndCloseDialog()`);
  keepOpenMore = false;
  if (curState.isOpen) {
    updateDialog({ isOpen: false, keysTyped: '', shortcutsToShow: [] });
  }
}



function onMouseMaybeReset(event: MouseEvent) {
  if (!curState.isOpen)
    return;

  if (curState.tryStayOpen && event.type === 'mousemove') {
    // Then don't close on mouse move events.
    return;
  }

  const dialogElm: HTMLElement | Z = curState.isOpen && $first('.c_KbdD');
  const targetElm: Element | Z = event.target as Element;

  if (dialogElm && dialogElm.contains(targetElm)) {
    // Don't close, if clicking or moving the mouse inside the shortcuts dialog.
    // Maybe pat wants to select and copy text? 
  }
  else {
    resetAndCloseDialog();
  }
}



function isShiftCtrl(key: St): Bo {
  return key === 'Shift';  // no, don't use Control:  || key === 'Control';
                           // makes the dialog pop up too often by mistake
}



function canBeShortcutKey(key: St): Bo {
  // All shortcuts are a-z combinations, at least for now.
  // No numbers, for now.
  const isAlnum = 'a' <= key && key <= 'z';
  const isBackspace = key === 'Backspace'
  return isAlnum || isBackspace || isShiftCtrl(key);
}



function onKeyUp(event: KeyboardEvent) {
  const key: St = event.key;
  logD(`onKeyUp: ${key}`);

  if (!curKeyDown) {
    // Pressed Ctrl+Tab or Ctrl+W (close) in another tab, and now released Ctrl
    // in this tab? So there's a key-up but no key-down event? — Ignore.
    return;
  }

  if (curKeyDown === key) {
    curKeyDown = '';
  }

  if (!canBeShortcutKey(key)) {
    resetAndCloseDialog();
    return;
  }

  // In Talkyard, Shift and Ctrl modifiers open the shortcuts dialog (or closes
  // and cancels), but are never part of the shortcuts.
  //
  // However, on Shift or Ctrl click, don't open the shortcut dialog until
  // pat releases the key — because otherwise the dialog would appear briefly,
  // if typing e.g. Ctrl+T to open a new browser tab.
  //
  if (isShiftCtrl(key)) {
    if (curState.isOpen) {
      resetAndCloseDialog();
    }
    else if (skipNextShiftCtrlUp) {
      // Don't open dialog. Otherwise, if pressing Ctrl+R, and realeasing R
      // then Ctrl, that'd open the dialog briefly, until page done reloading.
    }
    else if (anyOtherDialogOpen()) {
      // Don't open the shortcuts dialog on top of another dialog.
    }
    else {
      // If opening explicitly via Shift or Ctrl, then, don't close if using
      // the mouse — maybe pat wants to copy text?
      // And hopefully pat understands that since Shift and Ctrl opens,
      // those same buttons also close the dialog?
      const store: Store = getMainWinStore();
      const allShortcuts = makeMyShortcuts(store, '');
      const shortcutsToShow = findMatchingShortcuts(allShortcuts, '');
      updateDialog({ isOpen: true, tryStayOpen: true,
            keysTyped: '', shortcutsToShow });
    }

    // (Ignore both being pressed at the same time, for now.)
    shiftOrCtrlDown = false;
    skipNextShiftCtrlUp = false;
  }
}



function onKeyDown(event: KeyboardEvent) {
  const key: St = event.key;
  const otherKeyAlreadyDown = curKeyDown;
  curKeyDown = key;

  logD(`onKeyDown: ${key}`);

  // Handled in onKeyUp() instead.
  if (isShiftCtrl(key)) {
    shiftOrCtrlDown = true;
    // Must not click Shift or Ctrl together with the shortcut keys.
    if (otherKeyAlreadyDown) {
      resetAndCloseDialog();
    }
    return;
  }

  // Ctrl, Alt, Shift are never part of shortcuts.
  if (event.ctrlKey || event.altKey || event.shiftKey) {
    skipNextShiftCtrlUp ||= shiftOrCtrlDown;
    resetAndCloseDialog();
    return;
  }

  // If shortcut dialog open, skip the browser's default key event. Otherwise
  // e.g. clicking Space, would scroll down — which feels weird, since the
  // shortcut dialog had focus, not the page in the background.
  if (curState.isOpen) {
    event.preventDefault();
  }

  if (!canBeShortcutKey(event.key)) {
    // So Ctrl+R or Ctrl+T etc won't open the dialog, when one releases Ctrl.
    skipNextShiftCtrlUp ||= shiftOrCtrlDown;
    resetAndCloseDialog();
    return;
  }

  if (anyOtherDialogOpen())
    return;

  // If pat is typing text or selecting something. skip shortcuts.
  const anyTagName: St | Nl = (event.target as Element | Nl)?.tagName;
  if (anyTagName === 'INPUT' || anyTagName === 'TEXTAREA' || anyTagName === 'SELECT') {
    resetAndCloseDialog();
    return;
  }

  let keysTyped = curState.keysTyped;

  if (key === 'Backspace') {
    if (keysTyped) {
      // Undo any last key, and proceed below to show the thereafter
      // matching shortcuts (or all shortcuts, if keysTyped becomes '' empty).
      keysTyped = keysTyped.slice(0, keysTyped.length - 1);
    }
    else {
      // Don't open the dialog if just pressing Backspace. Or, close,
      // if hitting Backspace so all keys pressed "gone".
      resetAndCloseDialog();
      return;
    }
  }
  else {
    // @ifdef DEBUG
    dieIf(key.length > 1, 'TyE395MRKT');
    // @endif
    keysTyped += key;
  }

  const store: Store = getMainWinStore();
  const allShortcuts = makeMyShortcuts(store, keysTyped);
  const matchingShortcutsAndInfo: ShortcFnInfoZ[] = findMatchingShortcuts(
          allShortcuts, keysTyped);

  // Info items have no shortcut fn.
  const matchingShortcuts: ShorcFnItem[] =
          matchingShortcutsAndInfo.filter(item => !!item[ShortcutFnIx]) as ShorcFnItem[];

  const numMatching = matchingShortcuts.length;
  logD(`Num matching shortcuts: ${numMatching}, keysTyped: ${keysTyped}`);

  if (numMatching === 0 && keysTyped.length) {
    // Typed a Not-a-shortcut key, so cancel.
    resetAndCloseDialog();
    return;
  }

  if (numMatching === 1) {
    const shortcutToRun: ShorcFnItem = matchingShortcuts[0];
    const [keys, descr, shortcutFn] = shortcutToRun;
    if (keys.length === keysTyped.length) {
      logD(`RUNNING SHORTCUT FOR ${keysTyped}:`);
      shortcutFn(store);
      resetAndCloseDialog();
      return;
    }
  }
  // Else: Wait for more keys,
  // and show which shortcuts match the keys pressed this far:

  // If we didn't type anything yet (just hit Shift to open the dialog),
  // then list all shortcuts and info texts.
  const whatToShow = keysTyped ? matchingShortcutsAndInfo : allShortcuts;

  updateDialog({ isOpen: true, tryStayOpen: curState.tryStayOpen,
        keysTyped, shortcutsToShow: whatToShow });
}



function updateDialog(nextState: DiagState) {
  if (!dialogSetState) {
    // Why does render() return null?
    ReactDOM.render(KeyboardShortcutsDialog(), utils.makeMountNode());
  }

  if (!_.isEqual(nextState, curState)) {
    logD(`updateDialog({ ${JSON.stringify(nextState)} })`);
    dialogSetState(nextState);
    curState = nextState;
  }
  else {
    logD(`Need not updateDialog(), state unchanged: { ${JSON.stringify(nextState)} }`);
  }
}



const KeyboardShortcutsDialog = React.createFactory<{}>(function() {
  const [state, setState] = React.useState<DiagState | Nl>(null);

  dialogSetState = setState;

  if (!state || !state.isOpen)
    return r.div({});

  const firstKey = state.keysTyped[0];

  const boldKeysTyped = r.b({ className: 's_KbdD_KeysTyped' }, state.keysTyped);
  const numKeysTyped = state.keysTyped.length;

  function PrettyShortcut(item: ShortcFnInfoZ): RElm | Z {
    if (!item) return null;
    const [keys, descr, anyFn] = item;
    const isInfo = !anyFn;
    const keysLeft = keys.slice(numKeysTyped);
    return r.li({ key: keys },
        isInfo ? null : r.span({ className: 's_KbdD_ShortcutKeys' },
          boldKeysTyped, keysLeft + ' '),
        r.span({ className: 's_KbdD_Descr' + (isInfo ? ' s_KbdD_InfoIt' : '') },
          descr));
  }

  const title = state.keysTyped
      // Hmm, styles on b but not strong :-/ oh well.
      ? rFr({}, `Typing shortcut: `, r.b({}, state.keysTyped))
          // Why <blink>_</blink> won't work o.O  to simulate a type-more cursor?
          // Browsers bring <blink> back
      : `Keyboard shortcuts:`;

  const orTouchingTheMouse = state.tryStayOpen ? '' : ", or moving the mouse,";

  return InstaDiag({
    diagClassName: 'c_KbdD',
    title,
    body: rFr({},
        r.p({}, r.code({}, "Escape"), ',', r.code({}, "Space"),
            orTouchingTheMouse + " cancels."),
        r.ul({ className: 's_KbdD_ShortcutsL' },
          state.shortcutsToShow.map(PrettyShortcut))),
  });
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=tcqwn list
