// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Story
{
    class StoryController : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            InitBackground();                                          // IL_0001
            StartCoroutine(StoryTelling());                            // IL_0008
        }

        [UnityLifecycle]
        // reached from: StoryController.Start
        // confidence derived
        IEnumerator StoryTelling()
        {
            local i = 0;                                               // IL_0033

            if (i < StoryController.scriptContainer.GetScriptNum())
            {
                StoryController.scriptContainer.GetScriptData(i);          // IL_004A
                StoryController.SwitchBackground(ChatWindowData.background); // IL_0054
                StoryController.chatWindowController.SetAnyKeyPromptVisible(false); // IL_0060
                StoryController.scriptContainer.GetScriptData(i);          // IL_0077
                StoryController.scriptContainer.GetScriptData(i);          // IL_008D
                StoryController.chatWindowController.UpdateChatStream(ChatWindowData.name, ChatWindowData.text); // IL_0097
                yield return new WaitUntil(() => /* lambda in StoryTelling */); // IL_009E
                goto IL_003D;                                              // loop
            }

            StoryController.chatWindowController.IsStreaming;          // IL_00C9

            if (StoryController.chatWindowController.IsStreaming != 0)
            {
                StoryController.chatWindowController.CompleteStream();     // IL_00D6
            }

            yield return new WaitUntil(StoryController.IsAdvanceKeyDown); // IL_00F4

            StoryController.chatWindowController.SetAnyKeyPromptVisible(false); // IL_0120

            local i = (i + 1);                                         // IL_0147

            StoryController.scriptContainer.GetScriptNum();            // IL_0158
            goto IL_003D;                                              // loop

            if (i >= StoryController.scriptContainer.GetScriptNum())
            {
                StoryController.LoadMapScene();                            // IL_0163
            }
        }

        [UnityLifecycle]
        // reached from: StoryController.Start
        // confidence partial; gaps: callee-condition-not-composed
        void LoadMapScene()
        {
            if (MapMove.StagePosition == 5)
            {
                SceneManager.LoadScene("TitleScene");                      // IL_000D
            }

            if (MapMove.StagePosition != 5)
            {
                SceneManager.LoadScene("Map_scene");                       // IL_0018
            }
        }

        [UnityLifecycle]
        // reached from: StoryController.Start
        // confidence partial; gaps: callee-condition-not-composed
        void SwitchBackground(int p0)
        {
            // unresolved condition (subject lost): i == id
            // unresolved condition (subject lost): i < StoryController.backgorunds.Count
            StoryController.backgorunds.Item[_].SetActive(true);       // IL_0015

            // unresolved condition (subject lost): i != id
            // unresolved condition (subject lost): i < StoryController.backgorunds.Count
            StoryController.backgorunds.Item[_].SetActive(false);      // IL_0029

            if (id == 1)
            {
                StoryController.audioSource.Play();                        // IL_005B
            }
        }

        [UnityLifecycle]
        // reached from: StoryController.Start
        // confidence derived
        void InitBackground()
        {
            StoryController.backgorunds.Item[0].SetActive(true);       // IL_000D

            // unresolved condition (subject lost): i < StoryController.backgorunds.Count
            StoryController.backgorunds.Item[_].SetActive(false);      // IL_0023
            goto IL_0016;                                              // loop
        }

        [UnityLifecycle]
        // reached from: TutorialController.Update, StoryController.Start
        // confidence partial; gaps: composed-on-same-object, input-not-branched, reached-through-delegate
        bool IsAdvanceKeyDown()
        {
            // path: TutorialController.Update -> StoryController.IsAdvanceKeyDown
            if ((TutorialController.waitingForAcknowledge != 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (/* gesture: {"input": "key:any (down)", "offset": 0} */))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_0000
                if (Input.GetMouseButtonDown(2)) { ... }                   // IL_0018
            }

            // path: StoryController.Start -> StoryController.StoryTelling -> StoryController/<StoryTelling>d__8.MoveNext -> StoryController.IsAdvanceKeyDown
            if (/* gesture: {"input": "key:any (down)", "offset": 0} */)
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_0000
                if (Input.GetMouseButtonDown(2)) { ... }                   // IL_0018
                // control handed to UnityEngine.WaitUntil::.ctor @ IL_00F4
            }
        }

        [UnityLifecycle]
        // reached from: StoryController.Start
        // confidence partial; gaps: reached-through-delegate
        bool <StoryTelling>b__8_0()
        {
            StoryController.chatWindowController.IsStreaming;          // IL_0006
            // control handed to UnityEngine.WaitUntil::.ctor @ IL_009E

            if (StoryController.chatWindowController.IsStreaming != 0)
            {
                StoryController.IsAdvanceKeyDown();                        // IL_000D
                // control handed to UnityEngine.WaitUntil::.ctor @ IL_009E
            }
        }
    }
}
