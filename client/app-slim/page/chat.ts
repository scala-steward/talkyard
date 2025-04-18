/*
 * Copyright (c) 2016, 2017, 2024 Kaj Magnus Lindberg
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

/// <reference path="../ReactStore.ts" />
/// <reference path="../react-elements/name-login-btns.ts" />
/// <reference path="../Server.ts" />
/// <reference path="../utils/utils.ts" />
/// <reference path="../utils/window-zoom-resize-mixin.ts" />
/// <reference path="../avatar/avatar.ts" />
/// <reference path="../avatar/AvatarAndName.ts" />
/// <reference path="discussion.ts" />
/// <reference path="cats-or-home-link.ts" />
/// <reference path="../more-bundle-not-yet-loaded.ts" />
/// <reference path="../editor-bundle-not-yet-loaded.ts" />

//------------------------------------------------------------------------------
   namespace debiki2.page {
//------------------------------------------------------------------------------

const r = ReactDOMFactories;

const EditorBecomeFixedDist = 5;
const DefaultEditorRows = 2;


export const ChatMessages = createComponent({
  displayName: 'ChatMessages',

  componentDidUpdate: function() {
    // We should call onScroll() if a new message gets inserted below the current scroll pos.
    // Simply call it always, instead.
    this.refs.fixedAtBottom.onScroll();
  },

  scrollDown: function() {
    this.refs.titleAndMessages.scrollDown();
  },

  render: function() {
    const store: Store = this.props.store;
    const page: Page = store.currentPage;
    // Use [me_isPageMember] instead, in case any group user is in, is a member?
    const isChatMember = _.some(store.currentPage.pageMemberIds, id => id === store.me.id);
    const editorOrJoinButton = isChatMember || page.pageRole === PageRole.JoinlessChat
        ? ChatMessageEditor({ store: store, scrollDownToViewNewMessage: this.scrollDown })
        : JoinChatButton({});
    return (
      r.div({ className: 'esChatPage dw-page', id: 't_PageContent' },
        CatsOrHomeLink({ page, store }),
        TitleAndLastChatMessages({ store: store, ref: 'titleAndMessages' }),
        FixedAtBottom({ ref: 'fixedAtBottom' },
          editorOrJoinButton)));
  }
});



interface TitleAndChatMsgsState {
  lastEndDir?: RangeDir
  numMsgsInMem?: Nr
  skipTopUntil?: Nr
  hasScrolledDown?: Bo
}

const maxToShow = 100; // [max_chat_msgs_2_show]
const batchSize = 20;


const TitleAndLastChatMessages = createComponent({
  displayName: 'TitleAndLastChatMessages',

  getInitialState: function() {
    return {};
  },

  componentDidMount: function() {
    this.scrollDown();
    this.setState({ hasScrolledDown: true });

    const observer = new IntersectionObserver((entries: IntersectionObserverEntry[],
          _obsInit: IntersectionObserverInit) => {
      logD(`In observer clbk, ${entries.length} entries.`)
      let isAtTop = false;
      let isAtBottom = false;
      for (let e of entries) {
        if (!e.isIntersecting) continue;
        if (e.target.className.indexOf('c_Chat_Top') >= 0) isAtTop = true;
        if (e.target.className.indexOf('c_Chat_Bottom') >= 0) isAtBottom = true;
      }
      logD(`isAtTop: ${isAtTop}, bottom: ${isAtBottom}`);
      if (isAtTop !== isAtBottom) {
        // Later: If showing recent first, then flip Older/Newer.
        // COULD_OPTIMIZE: This makes us try to load new messages, if scrolling up a tiny
        // bit from the bottom, and then down. But that's unnecessary — chat msgs should
        // be pushed via websocket. Only if one has been disconnected for a while
        // does loading-more make sense?
        this.showMoreMessages(isAtTop ? RangeDir.Older : RangeDir.Newer);
      }
    }, {
      root: null, // means the viewport
      rootMargin: '0px',
      threshold: 0.01, // that's 1%
    });

    observer.observe(this.refs.topRef);
    observer.observe(this.refs.bottomRef);
    this.observer = observer;
  },

  componentWillUnmount: function() {
    this.isGone = true;
    if (this.observer) {
      this.observer.disconnect();
    }
  },

  UNSAFE_componentWillUpdate: function() {
    // Scroll down, if comment added, & we're at the bottom already.
    const pageColumnRect = getPageRect();
    // Add +2 because sometimes .bottom is 0.1 more than the-win-height, for some weird reason.
    this.shallScrollDown = pageColumnRect.bottom <= window.innerHeight + 2;
  },

  componentDidUpdate: function() {
    if (this.shallScrollDown) {
      this.scrollDown();
    }
  },

  scrollDown: function() {
    const pageColumn = document.getElementById('esPageColumn');
    pageColumn.scrollTop = pageColumn.scrollHeight;
  },

  showMoreMessages: function (scrollDir: RangeDir) {
    logD(`showMoreMessages(${scrollDir})`);
    const state: TitleAndChatMsgsState = this.state;
    const store: Store = this.props.store;
    const afterNr = scrollDir === RangeDir.Newer ? this.newestShownNr : this.oldestShownNr;
    let afterRectBef: Rect;

    // Currently chat messages are always chronological, so up = older.
    const scrollingUp = scrollDir === RangeDir.Older;
    const scrollingDown = scrollDir === RangeDir.Newer;

    const hasMoreInMem = _.isNumber(state.skipTopUntil);

    if (hasMoreInMem) {
      // Note that scrollDir is +-1.
      const newSkipTopUntil = state.skipTopUntil + scrollDir * batchSize;
      const newSkipBottomAfter = newSkipTopUntil + maxToShow;
      const needLoadMore = scrollingUp && newSkipTopUntil <= 0 ||
                            scrollingDown && newSkipBottomAfter >= state.numMsgsInMem;

      if (!needLoadMore) {
        const newState: Partial<TitleAndChatMsgsState> = {
          lastEndDir: scrollDir,
          skipTopUntil: newSkipTopUntil,
        };
        rememberScrollTop();
        this.setState(newState);
        // Adjust scrollTop so all messages stay at the same position in the viewport.
        // (Otherwise can be hard to see what happens, or look jerky.)
        requestAnimationFrame(updateScrollTop);
        return;
      }
    }

    const offset = afterNr + scrollDir; // skips one (we have it already)

    // Posts with lower numbers are the title, orig post and private posts (e.g. bookmarks).
    if (offset < PostNrs.FirstReplyNr && scrollingUp)
      return;

    // This patches the store (adds more chat messages), and adjusts the scroll
    // position so the chat messages already visible stay in the same positions.
    Server.loadPagePartsJson({ pageId: store.currentPageId,
          comtOrder: PostSortOrder.OldestFirst, // always, for chats
          offset,
          scrollDir,
          onOkBeforePatch: rememberScrollTop,
          onOkAfterPatch: (patchedStore: Store) => {
            if (this.isGone) return;

            // If there are really many chat messages, don't render all (or the browser might
            // get sluggish). 100 is a guess, maybe 500 is fine or 50 is a lot on mobiles?
            const page: Page = patchedStore.currentPage;
            let numMsgsInMem = 0;
            _.each(page.postsByNr, (post: Post) => {
              if (post.nr >= PostNrs.FirstReplyNr) numMsgsInMem += 1;
            });

            // If we loaded so many messages so there's now too many to show all at once
            // (for performance reasons), then, if we're scrolling up, skip the messages
            // at the bottom. If scrolling down, skip the ones at the top.
            //
            // This assumes that we don't load so many messages from the server so the ones
            // previously visible on screen gets skipped (because of `maxToShow`). We load
            // only 25, see [chat_pagination_size], that's much smaller than `maxToShow`.
            //
            let skipTopUntil = null;
            if (numMsgsInMem > maxToShow) {
              if (scrollDir === RangeDir.Older) {
                // Scrolling up. Render all messages we just loaded, they'll be at the top. 
                // (But we won't render the `numMsgsInMem - maxToShow` messages at the bottom.)
                skipTopUntil = 0;
              }
              else {
                // Scrolling down. Skip the "too many" messages at the top, but render
                // all messages at the bottom (those are the ones we just loaded).
                skipTopUntil = numMsgsInMem - maxToShow;
              }
            }

            // Remember the scroll direction, so, if there are too many messages,
            // we know if we should hide messages at the top, or bottom, so there
            // will be fewer to render.
            // (Here we still haven't rerendered the page; we can still edit the
            // state, to affect the next rendering ..._ctd)
            const newState: Partial<TitleAndChatMsgsState> = {
              lastEndDir: scrollDir,
              numMsgsInMem,
              skipTopUntil,
            };
            this.setState(newState);
            requestAnimationFrame(updateScrollTop);
    }});

    function rememberScrollTop() {
      if (this.isGone) return;
      // (The elem might be outside the viewport, fine.)
      const afterElm = document.getElementById('post-' + afterNr);
      afterRectBef = afterElm && afterElm.getBoundingClientRect();
    }

    function updateScrollTop() {
      // (_ctd... But here, the page has been rerendered — however, the screen has
      // not yet been repainted (right?), so we can adjust the scroll position
      // without any flash-of-wrong-scroll-position.)
      if (this.isGone) return;
      const afterElm2 = document.getElementById('post-' + afterNr);
      if (!afterRectBef || !afterElm2) return;
      const afterRectAft = afterElm2.getBoundingClientRect();
      const pageColumn = document.getElementById('esPageColumn');
      const jumpedDownDist = afterRectAft.top - afterRectBef.top;
      pageColumn.scrollTop = pageColumn.scrollTop + jumpedDownDist;
    }
  },

  render: function () {
    const state: TitleAndChatMsgsState = this.state;
    const store: Store = this.props.store;
    const page: Page = store.currentPage;
    const title = Title({ store }); // later: only if not scrolled down too far

    const originalPost = page.postsByNr[store.rootPostId];
    const origPostAuthor = store.usersByIdBrief[originalPost.authorId];
    //const origPostHeader = PostHeader({ store, post: originalPost });
    const origPostBody = PostBody({ store, post: originalPost });
    let canScrollUpToFetchOlder = true;

    let oldestShownNr = Number.MAX_SAFE_INTEGER;
    let newestShownNr = PostNrs.FirstReplyNr - 1;

    const skipBottomAfter = _.isNumber(state.skipTopUntil) ?
            state.skipTopUntil + maxToShow : Number.MAX_SAFE_INTEGER;

    // Chat messages don't have any parent post, aren't replies to the OP. [CHATPRNT]
    const messages = [];
    let ix = 0;

    _.each(page.postsByNr, (post: Post) => {

      // Skip title & body (they're shown as title & description instead),
      // and bookmarks (shown in message headers instead)
      // and private comments (not impl). [priv_comts]
      // But show previews of new messages — they have negative nrs [preview_id_nr_lt_0].
      if (post.nr < PostNrs.FirstReplyNr && !(post.isPreview && post.nr < 0))
        return;

      ix += 1;
      if (ix < state.skipTopUntil || skipBottomAfter < ix)
        return;

      if (!post.isPreview) {
        if (post.nr < oldestShownNr)
          oldestShownNr = post.nr;

        if (post.nr > newestShownNr)
          newestShownNr = post.nr;
      }

      if (post.isPostDeleted) {
        messages.push(DeletedChatMessage({ key: post.uniqueId, store: store, post: post }));
        return;
      }

      if (post.nr === FirstReplyNr) {
        // (COULD make this work also if post nr FirstReplyNr has been moved to another page
        // and hence will never be found. Fix by scrolling up, noticing that nothing was found,
        // and remove the you-can-scroll-up indicator?)
        canScrollUpToFetchOlder = false;
      }

      const postProps = { key: post.uniqueId, store, post };
      const postElem =
          post.postType === PostType.MetaMessage ? MetaPost(postProps) : ChatMessage(postProps);
      messages.push(postElem);
    });

    this.oldestShownNr = oldestShownNr;
    this.newestShownNr = newestShownNr;

    if (!messages.length) {
      canScrollUpToFetchOlder = false;
    }

    const thisIsTheWhat =
        r.p({},
          t.c.About_1 + ReactStore.getPageTitle() + t.c.About_2,
          avatar.AvatarAndName({ user: origPostAuthor, origins: store, hideAvatar: true }),
          ", ", timeExact(originalPost.createdAtMs));

    let perhapsHidden;
    if (!state.hasScrolledDown) {
      // Avoid flash of earlier messages before scrolling to end.
      perhapsHidden = { display: 'none' };
    }

    // When the chat top or bottom elems scroll into view, we'll load more messages from
    // the server.
    // When can't scroll (because not many messages), don't remove them, just set
    // visibility: hidden — otherwise the IntersectionObserver won't work.
    const visHidden = { visibility: 'hidden' };
    const hideUpTips = canScrollUpToFetchOlder ? undefined : visHidden;

    const scrollUpTips =
      r.div({ className: 'c_Chat_Top', ref: 'topRef', style: hideUpTips },
        r.button({ onClick: () => this.showMoreMessages(RangeDir.Older) },
          "Loading more ..."), // I18N & below
        ); // t.c.ScrollUpViewComments);

    // Let's always hide for now, simpler.
    const scrollDownTips =
      r.div({ className: 'c_Chat_Bottom', ref: 'bottomRef', style: visHidden },
        r.button({ onClick: () => this.showMoreMessages(RangeDir.Newer) },
          "Load more ..."));

    return (
      r.div({ className: 'esLastChatMsgs', style: perhapsHidden },
        title,
        r.div({ className: 'esChatChnl_about'},
          thisIsTheWhat,
          r.div({}, t.c.Purpose), // [chat_purpose_header]
          origPostBody),
        scrollUpTips,
        messages,
        scrollDownTips));
  }
});



const ChatMessage = createComponent({
  displayName: 'ChatMessage',

  getInitialState: function() {
    return { isEditing: false };
  },

  edit: function() {
    this.setState({ isEditing: true });
    const post: Post = this.props.post;
    // Later: Pass alias, if any.  [anon_chats]
    editor.openToEditPostNr(post.nr, (wasSaved, text) => {
      this.setState({ isEditing: false });
    });
  },

  delete_: function(event: MouseEvent) {
    // Later: [anon_chats].
    morebundle.openDeletePostDialog({ post: this.props.post, at: cloneEventTargetRect(event) });
  },

  render: function() {
    const state = this.state;
    const store: Store = this.props.store;
    const me: Myself = store.me;
    const post: Post = this.props.post;
    const author: BriefUser = store_getAuthorOrMissing(store, post);
    const headerProps: any = { store, post };
    headerProps.isFlat = true;
    headerProps.exactTime = true;

    const isMine = me.id === author.id ||
        // And, for now, for new post previews: [305KGWGH2]
        author.id === UnknownUserId;
    const isMineClass = isMine ? ' s_My' : '';

    const mayDelete = post.postType === PostType.ChatMessage && !state.isEditing &&
        !post.isPreview && (isMine || isStaff(me));
    const mayEdit = mayDelete && !store.isEditorOpen;

    if (mayEdit || mayDelete) {
      headerProps.stuffToAppend = rFragment({},
        !mayEdit ? null :
          r.button({ className: 's_C_M_B s_C_M_B-Ed icon-edit' + isMineClass,
              onClick: this.edit },
            t.c.edit),
        // (Don't show a trash icon, it'd make the page look too cluttered.)
        !mayDelete ? null :
          r.button({className: 's_C_M_B s_C_M_B-Dl' + isMineClass,
              onClick: this.delete_ },
            t.c.delete));
    }

    const isPreviewClass = post.isPreview ? ' s_C_M-Prvw' : '';

    //headerProps.stuffToAppend.push(
    //  r.button({ className: 'esC_M_MoreB icon-ellipsis', key: 'm' }, "more"));
    const chatMessage = (
      r.div({ className: 'esC_M' + isPreviewClass, id: 'post-' + post.nr },
        avatar.Avatar({ user: author, origins: store, size: AvatarSize.Small }),
        PostHeader(headerProps),
        PostBody({ store: store, post: post })));

    const isEditingExistingPost = post.nr >= MinRealPostNr;

    const anyPreviewInfo = !post.isPreview ? null :
        r.div({ className: 's_T_YourPrvw' },
          t.e.PreviewC + ' ',
          r.span({ className: 's_T_YourPrvw_ToWho' },
            isEditingExistingPost ?
            t.d.YourEdits : t.d.YourChatMsg));

    return (anyPreviewInfo ?
        rFragment({}, anyPreviewInfo, chatMessage) : chatMessage);
  }
});



function DeletedChatMessage(props) {
  const post: Post = props.post;
  return (
    r.div({ className: 'esC_M s_C_M-Dd', id: 'post-' + post.nr, key: props.key },
      r.div({ className: 'dw-p-bd' },
        r.div({ className: 'dw-p-bd-blk' },
          t.c.MessageDeleted))));
}



const FixedAtBottom = createComponent({
  displayName: 'FixedAtBottom',
  mixins: [utils.PageScrollMixin, utils.WindowZoomResizeMixin],

  getInitialState: function() {
    return { fixed: false, bottom: 0 };
  },

  componentDidMount: function() {
    // Currently we always scroll to the bottom, when opening a chat channel.
    // Later: setState fixed: true, if going back to a chat channel when one has scrolled up.
  },

  onWindowZoomOrResize: function() {
    this.onScroll();
  },

  onScroll: function() {
    const pageBottom = getPageRect().bottom;
    const scrollableBottom = window.innerHeight;
    const myNewBottom = pageBottom - scrollableBottom;
    this.setState({ bottom: myNewBottom });
    if (!this.state.fixed) {
      if (pageBottom > scrollableBottom + EditorBecomeFixedDist) {
        this.setState({ fixed: true });
      }
    }
    else {
      // Add +X otherwise sometimes the fixed state won't vanish although back at top of page.
      if (pageBottom - scrollableBottom <= +2) {
        this.setState({ fixed: false, bottom: 0 });
      }
    }
  },

  render: function () {
    let offsetBottomStyle;
    if (this.state.fixed) {
      offsetBottomStyle = { bottom: this.state.bottom };
    }
    return (
      r.div({ className: 'esFixAtBottom', style: offsetBottomStyle },
        React.cloneElement(this.props.children, {
          refreshFixedAtBottom: this.onScroll,
        })));
  }
});



const JoinChatButton = createComponent({
  displayName: 'JoinChatButton',

  componentWillUnmount: function() {
    this.isGone = true;
  },

  joinChannel: function() {
    login.loginIfNeededReturnToAnchor(LoginReason.LoginToChat, '#theJoinChatB', () => {
      if (this.isGone) {
        // Now after having logged in, this join chat button got removed (unmounted) — that's
        // because we've joined the chat already (some time long ago). So, need do nothing, now.
        return;
      }
      Server.joinPage();
    });
  },

  render: function() {
    return (
      r.div({ className: 'esJoinChat' },
        PrimaryButton({ id: 'theJoinChatB', className: 'esJoinChat_btn',
            onClick: this.joinChannel },
          t.c.JoinThisChat)));
  }
});



interface ChatMessageEditorState {
  text: string;
  draft?: Draft;
  draftStatus: DraftStatus;
  draftErrorStatusCode?: number;
  isSaving?: boolean;
  isLoading?: boolean;
  rows: number;
  advancedEditorInstead?: boolean;
  previewYPos: number;
  scriptsLoaded?: boolean;
}


// SMALLER_BUNDLE move to editor script bundle? ... Hmm, could be inline-editor-bundle.js?
// or editor-shell.js?
// and the full-text-with-preview could be  advanced-editor-bundle.js?
const ChatMessageEditor = createFactory<any, ChatMessageEditorState>({
  displayName: 'ChatMessageEditor',

  getInitialState: function(): ChatMessageEditorState {
    return {
      text: '',
      draftStatus: DraftStatus.NotLoaded,
      rows: DefaultEditorRows,
      previewYPos: 0,
    };
  },

  componentDidMount: function() {
    // Sync delay w e2e test. Dupl code. [upd_ed_pv_delay]
    this.updatePreviewSoon = _.debounce(this.updatePreviewNow, 333);

    this.saveDraftSoon = _.debounce(() => {
      if (this.isGone) return;
      this.saveDraftNow();  // [7AKBJ42]
    }, 2022);

    window.addEventListener('unload', this.saveDraftUseBeacon);

    // Load editor scripts — but why??? skip? (WAITWJS) and any draft text.
    Server.loadEditorAndMoreBundles(() => {
      if (this.isGone) return;

      const store: Store = this.props.store;
      const page: Page = store.currentPage;
      const bodyPostId = page.postsByNr[BodyNr].uniqueId;

      const draftLocator: DraftLocator = {
        draftType: DraftType.Reply,
        pageId: page.pageId,
        postNr: BodyNr,
        postId: bodyPostId,  // ?? why incl here, but not when saving draft (50285RK)
      };
      const newState: Partial<ChatMessageEditorState> = { scriptsLoaded: true };
      this.setState(newState);
      Server.loadDraftAndGuidelines(draftLocator, WritingWhat.ChatComment,
          page.categoryId, page.pageRole,
          (guidelinesSafeHtml: string | U, draft?: Draft) => {
        if (this.isGone) return;
        const newState: Partial<ChatMessageEditorState> = {
          draft,
          draftStatus: DraftStatus.NothingHappened,
          text: draft ? draft.text : '',
        };
        this.setState(newState);
      });
    });
  },

  componentWillUnmount: function() {
    this.isGone = true;
    logD("ChatMessageEditor: componentWillUnmount")
    window.removeEventListener('unload', this.saveDraftUseBeacon);
    this.saveDraftNow();
  },

  saveDraftUseBeacon: function() {
    this.saveDraftNow(UseBeacon);
  },

  saveDraftNow: function(useBeacon?: UseBeacon) {
    // Tested here: TyT7JKMW24
    // A bit dupl code [4ABKR2J0]

    // Don't save draft from both here, and the advanced editor — then might get dupl drafts. [TyT270424]
    if (this.state.advancedEditorInstead)
      return;

    // If we're closing the page, do try saving anyway, using becaon, because the current non-beacon
    // request will probably be aborted by the browser? (since, if beacon, the page is getting unloaded)
    if (this.isSavingDraft && !useBeacon)
      return;

    const store: Store = this.props.store;
    const me: Myself = store.me;

    const oldDraft: Draft | undefined = this.state.draft;
    const draftStatus: DraftStatus = this.state.draftStatus;

    if (draftStatus <= DraftStatus.NeedNotSave)
      return;

    const forWhat: DraftLocator = {
      draftType: DraftType.Reply,
      pageId: store.currentPageId,
      postNr: BodyNr,
      // No postId here? (50285RK)
    };

    if (store.currentPage) {
      const post = store.currentPage.postsByNr[BodyNr];
      forWhat.postId = post ? post.uniqueId : undefined;
    }

    const draftOldOrEmpty: Draft = oldDraft || {
      byUserId: me.id,
      draftNr: NoDraftNr,
      forWhat,
      createdAt: getNowMs(),
      postType: PostType.ChatMessage,
      text: '',
    };

    const text: string = (this.state.text || '').trim();

    // BUG the lost update bug, unlikely to happen: Might overwrite other version of this draft [5KBRZ27].

    const withBeacon = useBeacon ? ', with beacon' : '';

    // If empty. Delete any old draft.  BUG [DRAFTS_BUG] preview doesn't get deleted properly
    if (!text) {
      if (oldDraft) {
        logD(`Deleting draft${withBeacon}...`);
        this.setState({ draftStatus: DraftStatus.Deleting });
        this.isSavingDraft = true;
        Server.deleteDrafts([oldDraft.draftNr], useBeacon || (() => {
          // DUPL CODE, bad, here & above [UPSDFTDUPLCD]
          this.isSavingDraft = false;
          logD("...Deleted draft.");
          if (this.isGone) return;
          this.setState({
            draft: null,
            draftStatus: DraftStatus.Deleted,
          });
        }), useBeacon || this.setCannotSaveDraft);
      }
      return;
    }

    const draftToSave = { ...draftOldOrEmpty, text, title: '' };
    this.setState({
      draftStatus: DraftStatus.SavingSmall,
    });

    logD(`Saving draft${withBeacon}: ${JSON.stringify(draftToSave)}`);
    this.isSavingDraft = true;
    Server.upsertDraft(draftToSave, useBeacon || ((draftWithNr: Draft) => {
      // DUPL CODE, bad, here & above [UPSDFTDUPLCD]
      logD("...Saved draft.");
      if (this.isGone) return;
      this.isSavingDraft = false;
      this.setState({
        draft: draftWithNr,
        draftStatus: DraftStatus.SavedServerSide,
      });
    }), useBeacon || this.setCannotSaveDraft);
  },

  setCannotSaveDraft: function(errorStatusCode?: number) {
    // Dupl code [4ABKR2JZ7]
    logW(`... Error saving draft, status: ${errorStatusCode}`);
    if (this.isGone) return;
    this.isSavingDraft = false;
    this.setState({
      draftStatus: DraftStatus.CannotSave,
      draftErrorStatusCode: errorStatusCode,
    });
  },

  onTextEdited: function(event) {
    this.updateText(event.target.value);
  },

  updateText: function(text, draftWithStatus?: { draft, draftStatus }) {
    const store: Store = this.props.store;
    const state: ChatMessageEditorState = this.state;

    // numLines won't work with wrapped lines, oh well, fix some other day.
    // COULD use https://github.com/andreypopp/react-textarea-autosize instead.
    const numLines = text.split(/\r\n|\r|\n/).length;

    // A bit dupl code [7WKABF2]
    const draft: Draft = state.draft;
    const draftStatus = draft && draft.text === text  // ? .trim()
      ? DraftStatus.EditsUndone
      : DraftStatus.ShouldSave;

    const textChanged = state.text !== text;
    const textNowEmpty = !text;  // isBlank(text); ?

    // COULD use store.isEditorOpen instead — but I think it hasn't been updated yet?
    if (textChanged && !this.state.advancedEditorInstead) {
      if (textNowEmpty) {
        ReactActions.hideEditorAndPreview({});
      }
      else {
        this.updatePreviewSoon();
      }
    }

    const newState: Partial<ChatMessageEditorState> = {
      text,
      draft: (draftWithStatus ? draftWithStatus.draft : this.state.draft),
      draftStatus: (draftWithStatus ? draftWithStatus.draftStatus : draftStatus),
      rows: Math.max(DefaultEditorRows, Math.min(8, numLines)),
    };

    this.setState(
        newState,
        draftStatus === DraftStatus.ShouldSave ? this.saveDraftSoon : undefined);

    // In case lines were deleted, we need to move the editor a bit downwards, so it
    // remains fixed at the bottom — because now it's smaller.
    if (this.props.refreshFixedAtBottom) {
      // In case the advanced editor is currently shown, use setTimeout() so we'll
      // refresh after the current render phase.
      setTimeout(() => {
        if (this.isGone) return;
        this.props.refreshFixedAtBottom();
      }, 0);
    }
  },

  updatePreviewNow: function() {
    Server.loadEditorAndMoreBundles(() => {  // needn't do until here? (WAITWJS)
      if (this.isGone) return;

      const sanitizerOpts = {
        allowClassAndIdAttr: true, // or only if isEditingBody?  dupl [304KPGSD25]
        allowDataAttr: false
      };

      const safeHtml = debiki2['editor'].markdownToSafeHtml(
          this.state.text, window.location.host, sanitizerOpts);

      // If one has scrolled up manually, so much so the preview is now below
      // the editor, then stop scrolling the preview into view — because
      // apparently the user wants to control the scroll henself.
      const previewElm = $first('.s_T_YourPrvw');
      const previewElmY = previewElm?.getBoundingClientRect()?.y || 0;
      // The simple chat message text box, or the advanced editor.
      const editorElm = $first('.esC_Edtr, .s_E-E');
      const editorElmY = editorElm?.getBoundingClientRect()?.y || 0;
      const scrollToPreview = previewElmY <= editorElmY;

      ReactActions.showEditsPreviewInPage({ scrollToPreview, safeHtml, highlightPreview: false });
    });
  },

  onKeyPressOrKeyDown: function(event) {
    // Let Return mean newline everywhere, and ctrl+return means Submit everywhere.
    // (Typically, in a chat, Return/Enter means "post my message". However, in Talkyard's
    // "advanced" editor, hitting Return adds a newline — so people (well at least
    // my (KajMagnus') father) get confused if Return instead submits one's chat message.)

    // In my Chrome, Ctrl + Enter won't fire onKeyPress, only onKeyDown. [5KU8W2]
    if (event_isCtrlEnter(event)) {
      const isNotEmpty = /\S/.test(this.state.text);
      if (isNotEmpty) {
        event.preventDefault();
        this.saveChatMessage();
      }
    }
  },

  saveChatMessage: function() {
    this.setState({ isSaving: true });
    ReactActions.insertChatMessage(this.state.text, this.state.draft, () => {
      if (this.isGone) return;
      ReactActions.hideEditorAndPreview({});
      const newState: Partial<ChatMessageEditorState> = {
        text: '',
        isSaving: false,
        draft: null,
        draftStatus: DraftStatus.NothingHappened,
        rows: DefaultEditorRows,
      };
      this.setState(newState);
      this.props.scrollDownToViewNewMessage();
      this.textareaElm?.focus();
    });
  },

  useAdvancedEditor: function() {
    this.setState({ advancedEditorInstead: true });
    const state = this.state;
    editor.openToWriteChatMessage(state.text, state.draft, state.draftStatus,
          (wasSaved, text, draft, draftStatus) => {
      if (this.isGone) return;
      // Now the advanced editor has been closed.
      this.setState({
        advancedEditorInstead: false,
      });
      this.updateText(wasSaved ? '' : text, { draft, draftStatus });
      if (wasSaved) {
        this.props.scrollDownToViewNewMessage();
      }
    });
  },

  render: function () {
    const store: Store = this.props.store;

    if (store.isEditorOpen || !this.state.scriptsLoaded ||
        // Can remove this check now? using  isEditorOpen  above instead
        this.state.advancedEditorInstead)
      return null;

    const state: ChatMessageEditorState = this.state;
    const draft: Draft = state.draft;
    const draftNr = draft ? draft.draftNr : NoDraftNr;
    const draftStatus: DraftStatus = state.draftStatus;
    const draftErrorStatusCode = state.draftErrorStatusCode;
    const draftStatusInfo =
        editor['DraftStatusInfo']({ draftStatus, draftNr, draftErrorStatusCode });

    // We'll disable the editor, until any draft has been loaded. [5AKBW20]
    const anyDraftLoaded = draftStatus !== DraftStatus.NotLoaded;

    const disabled = state.isLoading || !anyDraftLoaded || state.isSaving;
    const buttons =
        r.div({ className: 'esC_Edtr_Bs' },
          draftStatusInfo,
          r.button({ className: 'esC_Edtr_SaveB btn btn-primary', onClick: this.saveChatMessage,
              disabled: disabled },
            '↵ ' + t.c.PostMessage),
          r.button({ className: 'esC_Edtr_AdvB btn btn-default', onClick: this.useAdvancedEditor,
              disabled: disabled },
            t.c.AdvancedEditor));

    // In the editor scripts bundle, lazy loaded.
    const ReactTextareaAutocomplete = editor['ReactTextareaAutocomplete'];
    const listUsernamesTrigger = editor['listUsernamesTrigger'];

    return (
      r.div({ className: 'esC_Edtr' },
        // The @mentions username autocomplete might overflow the textarea. [J7UKFBW]
        ReactTextareaAutocomplete({ className: 'esC_Edtr_textarea',
          value: anyDraftLoaded ? state.text : t.e.LoadingDraftDots,
          onChange: this.onTextEdited,
          onKeyPress: this.onKeyPressOrKeyDown,
          onKeyDown: this.onKeyPressOrKeyDown,
          innerRef: (e: HTMLTextAreaElement) => {
            this.textareaElm = e;
            e && e.focus();
          },
          placeholder: t.c.TypeHere,
          disabled: disabled,
          rows: state.rows,
          loadingComponent: () => r.span({}, t.Loading),
          trigger: listUsernamesTrigger }),
        buttons));
  }
});

// Staying at the bottom: http://blog.vjeux.com/2013/javascript/scroll-position-with-react.html

//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=tcqwn list
