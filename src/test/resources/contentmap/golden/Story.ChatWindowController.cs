// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Story
{
    class ChatWindowController : MonoBehaviour
    {
        [UnityLifecycle]
        [InspectorCallable]
        // reached from: StoryController.Start, ChatWindowController.SetAnyKeyPromptVisible, ChatWindowController.CompleteStream, TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial, TutorialController.Start
        // called by: Story.StoryController/<StoryTelling>d__8.MoveNext, Tutorial.TutorialController.Update
        // confidence derived/partial; gaps: callee-condition-not-composed
        void SetAnyKeyPromptVisible(bool p0)
        {
            if (ChatWindowController.anyKeyPrompt != null)
            {
                ChatWindowController.anyKeyPrompt.SetActive((not a literal)); // IL_0016
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: StoryController.Start, ChatWindowController.CompleteStream, TutorialController.Update
        // called by: Story.StoryController/<StoryTelling>d__8.MoveNext, Tutorial.TutorialController.Update
        // confidence partial/verified; gaps: callee-condition-not-composed, composed-on-same-object
        void CompleteStream()
        {
            // path: ChatWindowController.CompleteStream | StoryController.Start -> StoryController.StoryTelling -> StoryController/<StoryTelling>d__8.MoveNext -> ChatWindowController.CompleteStream
            if (ChatWindowController.streamingCoroutine != 0)
            {
                ChatWindowController.streamingCoroutine = null;            // IL_0017
                ChatWindowController.chatText.text = ChatWindowController.streamingText, true; // IL_0029
                OnStreamComplete();                                        // IL_002F
            }

            // path: TutorialController.Update -> ChatWindowController.CompleteStream
            if ((TutorialController.tutorialChatWindow.IsStreaming != 0) && (StoryController.IsAdvanceKeyDown() != 0) && (TutorialController.waitingForAcknowledge != 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialChatWindow.streamingCoroutine != 0))
            {
                ChatWindowController.streamingCoroutine = null;            // IL_0017
                ChatWindowController.chatText.text = ChatWindowController.streamingText, true; // IL_0029
                OnStreamComplete();                                        // IL_002F
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: StoryController.Start, ChatWindowController.CompleteStream, TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial, TutorialController.Start
        // called by: Story.StoryController/<StoryTelling>d__8.MoveNext, Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived
        void OnStreamComplete()
        {
            // path: StoryController.Start -> StoryController.StoryTelling -> StoryController/<StoryTelling>d__8.MoveNext -> ChatWindowController.CompleteStream -> ChatWindowController.OnStreamComplete
            if ((StoryController.chatWindowController.IsStreaming != 0) && (ChatWindowController.streamingCoroutine != 0))
            {
                SetAnyKeyPromptVisible(true);                              // IL_0002
            }

            // path: ChatWindowController.CompleteStream -> ChatWindowController.OnStreamComplete
            if (ChatWindowController.streamingCoroutine != 0)
            {
                SetAnyKeyPromptVisible(true);                              // IL_0002
            }

            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext -> ChatWindowController.OnStreamComplete
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0) && (i >= ChatWindowController.streamingText.Length))
            {
                SetAnyKeyPromptVisible(true);                              // IL_0002
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext -> ChatWindowController.OnStreamComplete
            if ((TutorialController.tutorialCondition.IsMeetCondition() != 0) && (i >= ChatWindowController.streamingText.Length))
            {
                SetAnyKeyPromptVisible(true);                              // IL_0002
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext -> ChatWindowController.OnStreamComplete
            if (i >= ChatWindowController.streamingText.Length)
            {
                SetAnyKeyPromptVisible(true);                              // IL_0002
            }

            // path: TutorialController.Start -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext -> ChatWindowController.OnStreamComplete
            if ((MapMove.StagePosition <= 0) && (i >= ChatWindowController.streamingText.Length))
            {
                SetAnyKeyPromptVisible(true);                              // IL_0002
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: StoryController.Start, TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial, TutorialController.Start
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived
        void UpdateChatStream(string p0, string p1)
        {
            // path: StoryController.Start -> StoryController.StoryTelling -> StoryController/<StoryTelling>d__8.MoveNext -> ChatWindowController.UpdateChatStream
            if (i < StoryController.scriptContainer.GetScriptNum())
            {
                ChatWindowController.chatName.text = _, true;              // IL_001C
                ChatWindowController.streamingText = String.Concat(_, " "); // IL_002D
                StartCoroutine(UpdateStreamingChat());                     // IL_0035
                ChatWindowController.streamingCoroutine = this.StartCoroutine(ChatWindowController.UpdateStreamingChat()); // IL_003F
            }

            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                ChatWindowController.chatName.text = _, true;              // IL_001C
                ChatWindowController.streamingText = String.Concat(_, " "); // IL_002D
                StartCoroutine(UpdateStreamingChat());                     // IL_0035
                ChatWindowController.streamingCoroutine = this.StartCoroutine(ChatWindowController.UpdateStreamingChat()); // IL_003F
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                ChatWindowController.chatName.text = _, true;              // IL_001C
                ChatWindowController.streamingText = String.Concat(_, " "); // IL_002D
                StartCoroutine(UpdateStreamingChat());                     // IL_0035
                ChatWindowController.streamingCoroutine = this.StartCoroutine(ChatWindowController.UpdateStreamingChat()); // IL_003F
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream
            ChatWindowController.chatName.text = _, true;              // IL_001C
            ChatWindowController.streamingText = String.Concat(_, " "); // IL_002D
            StartCoroutine(UpdateStreamingChat());                     // IL_0035
            ChatWindowController.streamingCoroutine = this.StartCoroutine(ChatWindowController.UpdateStreamingChat()); // IL_003F

            // path: TutorialController.Start -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream
            if (MapMove.StagePosition <= 0)
            {
                ChatWindowController.chatName.text = _, true;              // IL_001C
                ChatWindowController.streamingText = String.Concat(_, " "); // IL_002D
                StartCoroutine(UpdateStreamingChat());                     // IL_0035
                ChatWindowController.streamingCoroutine = this.StartCoroutine(ChatWindowController.UpdateStreamingChat()); // IL_003F
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: StoryController.Start, TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.Start, TutorialController.OnTriggerTutorial
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived/partial; gaps: callee-condition-not-composed
        IEnumerator UpdateStreamingChat()
        {
            // path: StoryController.Start -> StoryController.StoryTelling -> StoryController/<StoryTelling>d__8.MoveNext -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat
            if (i < StoryController.scriptContainer.GetScriptNum())
            {
                // no observed statements
            }

            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                // no observed statements
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                // no observed statements
            }

            // path: TutorialController.Start -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat
            if (MapMove.StagePosition <= 0)
            {
                // no observed statements
            }

            // path: StoryController.Start -> StoryController.StoryTelling -> StoryController/<StoryTelling>d__8.MoveNext -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if (i < StoryController.scriptContainer.GetScriptNum())
            {
                local i = 0;                                               // IL_0020
            }

            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                local i = 0;                                               // IL_0020
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                local i = 0;                                               // IL_0020
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            local i = 0;                                               // IL_0020

            // path: TutorialController.Start -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if (MapMove.StagePosition <= 0)
            {
                local i = 0;                                               // IL_0020
            }

            // path: StoryController.Start -> StoryController.StoryTelling -> StoryController/<StoryTelling>d__8.MoveNext -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if (i < StoryController.scriptContainer.GetScriptNum())
            {
                ChatWindowController.chatText.text = ChatWindowController.streamingText.Substring(0, i), true; // IL_0060
                local i = (i + 1);                                         // IL_0070
            }

            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                ChatWindowController.chatText.text = ChatWindowController.streamingText.Substring(0, i), true; // IL_0060
                local i = (i + 1);                                         // IL_0070
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                ChatWindowController.chatText.text = ChatWindowController.streamingText.Substring(0, i), true; // IL_0060
                local i = (i + 1);                                         // IL_0070
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            ChatWindowController.chatText.text = ChatWindowController.streamingText.Substring(0, i), true; // IL_0060
            local i = (i + 1);                                         // IL_0070

            // path: TutorialController.Start -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if (MapMove.StagePosition <= 0)
            {
                ChatWindowController.chatText.text = ChatWindowController.streamingText.Substring(0, i), true; // IL_0060
                local i = (i + 1);                                         // IL_0070
            }

            // path: StoryController.Start -> StoryController.StoryTelling -> StoryController/<StoryTelling>d__8.MoveNext -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext | TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> ChatWindowController.UpdateChatStream -> ChatWindowController.UpdateStreamingChat -> ChatWindowController/<UpdateStreamingChat>d__11.MoveNext
            if (i >= ChatWindowController.streamingText.Length)
            {
                ChatWindowController.chatText.text = ChatWindowController.streamingText, true; // IL_0095
                ChatWindowController.streamingCoroutine = null;            // IL_009C
                ChatWindowController.OnStreamComplete();                   // IL_00A2
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Awake()
        {
            InitTexts();                                               // IL_0001
        }

        [UnityLifecycle]
        // reached from: ChatWindowController.Awake
        // confidence partial; gaps: unread-condition
        void InitTexts()
        {
            // unresolved condition (subject lost): Object.name == "ChatName"
            if ((/* unknown */))
            {
                ChatWindowController.chatName = /* ? */;                   // IL_0024
            }

            // unresolved condition (subject lost): Object.name == "ChatText"
            if ((/* unknown */))
            {
                ChatWindowController.chatText = /* ? */;                   // IL_003F
            }
        }
    }
}
