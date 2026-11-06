// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Tutorial
{
    class TutorialController : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void OnDisable()
        {
            InteractionLock.IsLocked = false;                          // IL_0001
            InteractionLock.IsLocked = 0;                              // IL_0001
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.OnDisable, TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial, TutorialController.Start
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived
        bool IsLocked { set; }
        {
            // path: TutorialController.OnDisable -> InteractionLock.set_IsLocked
            InteractionLock.IsLocked = value;                          // IL_0001

            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame -> TutorialController/<UnlockAfterFrame>d__15.MoveNext -> InteractionLock.set_IsLocked
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                InteractionLock.IsLocked = value;                          // IL_0001
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame -> TutorialController/<UnlockAfterFrame>d__15.MoveNext -> InteractionLock.set_IsLocked
            if ((TutorialController.tutorialCondition.IsMeetCondition() != 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                InteractionLock.IsLocked = value;                          // IL_0001
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame -> TutorialController/<UnlockAfterFrame>d__15.MoveNext -> InteractionLock.set_IsLocked
            if ((this.isActiveAndEnabled != 0) && (visible == 0))
            {
                InteractionLock.IsLocked = value;                          // IL_0001
            }

            // path: TutorialController.Start -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame -> TutorialController/<UnlockAfterFrame>d__15.MoveNext -> InteractionLock.set_IsLocked
            if ((MapMove.StagePosition <= 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                InteractionLock.IsLocked = value;                          // IL_0001
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Update()
        {
            if (Object.Equals(FLAG_014_END_TUTORIAL) != 0)
            {
                Component.gameObject.SetActive(false);                     // IL_0021
            }

            if ((TutorialController.waitingForAcknowledge != 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0))
            {
                StoryController.IsAdvanceKeyDown();                        // IL_002F
            }

            if ((StoryController.IsAdvanceKeyDown() != 0) && (TutorialController.waitingForAcknowledge != 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0))
            {
                TutorialController.tutorialChatWindow.IsStreaming;         // IL_003D
            }

            if ((TutorialController.tutorialChatWindow.IsStreaming != 0) && (StoryController.IsAdvanceKeyDown() != 0) && (TutorialController.waitingForAcknowledge != 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0))
            {
                TutorialController.tutorialChatWindow.CompleteStream();    // IL_004A
            }

            if ((TutorialController.tutorialChatWindow.IsStreaming == 0) && (StoryController.IsAdvanceKeyDown() != 0) && (TutorialController.waitingForAcknowledge != 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0))
            {
                TutorialController.waitingForAcknowledge = 0;              // IL_0052
                TutorialController.tutorialChatWindow.SetAnyKeyPromptVisible(false); // IL_005E
                SetChatWindowVisible(false);                               // IL_0065
            }

            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0))
            {
                ProceedTutorial();                                         // IL_006C
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.Update, TutorialController.ProceedTutorial
        // called by: Tutorial.TutorialController.Update
        // confidence partial/verified; gaps: composed-on-same-object
        void ProceedTutorial()
        {
            // path: TutorialController.Update -> TutorialController.ProceedTutorial
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                OnTriggerTutorial();                                       // IL_000E
            }

            // path: TutorialController.ProceedTutorial
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                OnTriggerTutorial();                                       // IL_000E
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived/verified
        void OnTriggerTutorial()
        {
            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                SetChatWindowVisible(true);                                // IL_0002
                GoNextFlag();                                              // IL_0008
                StoryTelling();                                            // IL_000E
                TutorialController.tutorialCondition = TutorialController.tutorialCondition.GetNextCondition(); // IL_001F
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                SetChatWindowVisible(true);                                // IL_0002
                GoNextFlag();                                              // IL_0008
                StoryTelling();                                            // IL_000E
                TutorialController.tutorialCondition = TutorialController.tutorialCondition.GetNextCondition(); // IL_001F
            }

            // path: TutorialController.OnTriggerTutorial
            SetChatWindowVisible(true);                                // IL_0002
            GoNextFlag();                                              // IL_0008
            StoryTelling();                                            // IL_000E
            TutorialController.tutorialCondition = TutorialController.tutorialCondition.GetNextCondition(); // IL_001F
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial, TutorialController.Start
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived
        void StoryTelling()
        {
            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                TutorialController.tutorialScript.GetScriptData(TutorialController.currentFlag); // IL_000C
                TutorialController.tutorialScript.GetSprite(TutorialChatData.portraitID); // IL_0024
                TutorialController.tutorialChatWindow.SetSpeakerImage(TutorialController.tutorialScript.GetSprite(TutorialChatData.portraitID)); // IL_0029
                TutorialController.tutorialChatWindow.SetAnyKeyPromptVisible(false); // IL_0035
                TutorialController.tutorialChatWindow.UpdateChatStream(TutorialChatData.name, TutorialChatData.text); // IL_004C
                TutorialController.waitingForAcknowledge = 1;              // IL_0053
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                TutorialController.tutorialScript.GetScriptData(TutorialController.currentFlag); // IL_000C
                TutorialController.tutorialScript.GetSprite(TutorialChatData.portraitID); // IL_0024
                TutorialController.tutorialChatWindow.SetSpeakerImage(TutorialController.tutorialScript.GetSprite(TutorialChatData.portraitID)); // IL_0029
                TutorialController.tutorialChatWindow.SetAnyKeyPromptVisible(false); // IL_0035
                TutorialController.tutorialChatWindow.UpdateChatStream(TutorialChatData.name, TutorialChatData.text); // IL_004C
                TutorialController.waitingForAcknowledge = 1;              // IL_0053
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling
            TutorialController.tutorialScript.GetScriptData(TutorialController.currentFlag); // IL_000C
            TutorialController.tutorialScript.GetSprite(TutorialChatData.portraitID); // IL_0024
            TutorialController.tutorialChatWindow.SetSpeakerImage(TutorialController.tutorialScript.GetSprite(TutorialChatData.portraitID)); // IL_0029
            TutorialController.tutorialChatWindow.SetAnyKeyPromptVisible(false); // IL_0035
            TutorialController.tutorialChatWindow.UpdateChatStream(TutorialChatData.name, TutorialChatData.text); // IL_004C
            TutorialController.waitingForAcknowledge = 1;              // IL_0053

            // path: TutorialController.Start -> TutorialController.StoryTelling
            if (MapMove.StagePosition <= 0)
            {
                TutorialController.tutorialScript.GetScriptData(TutorialController.currentFlag); // IL_000C
                TutorialController.tutorialScript.GetSprite(TutorialChatData.portraitID); // IL_0024
                TutorialController.tutorialChatWindow.SetSpeakerImage(TutorialController.tutorialScript.GetSprite(TutorialChatData.portraitID)); // IL_0029
                TutorialController.tutorialChatWindow.SetAnyKeyPromptVisible(false); // IL_0035
                TutorialController.tutorialChatWindow.UpdateChatStream(TutorialChatData.name, TutorialChatData.text); // IL_004C
                TutorialController.waitingForAcknowledge = 1;              // IL_0053
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial, TutorialController.Start
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived
        TutorialChatData GetScriptData(TutorialFlag p0)
        {
            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> TutorialScriptContainer.GetScriptData
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                // no observed statements
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> TutorialScriptContainer.GetScriptData
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                // no observed statements
            }

            // path: TutorialController.Start -> TutorialController.StoryTelling -> TutorialScriptContainer.GetScriptData
            if (MapMove.StagePosition <= 0)
            {
                // no observed statements
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived
        void GoNextFlag()
        {
            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.GoNextFlag
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                Extensions.Next(TutorialController.currentFlag);           // IL_0007
                TutorialController.currentFlag = Extensions.Next(TutorialController.currentFlag); // IL_000C
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.GoNextFlag
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                Extensions.Next(TutorialController.currentFlag);           // IL_0007
                TutorialController.currentFlag = Extensions.Next(TutorialController.currentFlag); // IL_000C
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.GoNextFlag
            Extensions.Next(TutorialController.currentFlag);           // IL_0007
            TutorialController.currentFlag = Extensions.Next(TutorialController.currentFlag); // IL_000C
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.Start, TutorialController.OnTriggerTutorial
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived/partial; gaps: callee-condition-not-composed, composed-on-same-object
        void SetChatWindowVisible(bool p0)
        {
            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                TutorialController.tutorialChatWindow.gameObject.SetActive((not a literal)); // IL_000C
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                TutorialController.tutorialChatWindow.gameObject.SetActive((not a literal)); // IL_000C
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible
            TutorialController.tutorialChatWindow.gameObject.SetActive((not a literal)); // IL_000C

            // path: TutorialController.Start -> TutorialController.SetChatWindowVisible
            if (MapMove.StagePosition <= 0)
            {
                TutorialController.tutorialChatWindow.gameObject.SetActive((not a literal)); // IL_000C
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible | TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible
            if (TutorialController.inputBlocker != null)
            {
                TutorialController.inputBlocker.SetActive((not a literal)); // IL_0026
            }

            // path: TutorialController.Start -> TutorialController.SetChatWindowVisible
            if ((MapMove.StagePosition <= 0) && (TutorialController.inputBlocker != null))
            {
                TutorialController.inputBlocker.SetActive((not a literal)); // IL_0026
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible | TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible
            if (visible != 0)
            {
                InteractionLock.IsLocked = true;                           // IL_002F
                InteractionLock.IsLocked = 1;                              // IL_002F
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible | TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible
            if ((this.isActiveAndEnabled == 0) && (visible == 0))
            {
                InteractionLock.IsLocked = false;                          // IL_003E
                InteractionLock.IsLocked = 0;                              // IL_003E
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible | TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible
            if ((this.isActiveAndEnabled != 0) && (visible == 0))
            {
                StartCoroutine(UnlockAfterFrame());                        // IL_0046
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial, TutorialController.Start
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived
        IEnumerator UnlockAfterFrame()
        {
            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                // no observed statements
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame
            if ((TutorialController.tutorialCondition.IsMeetCondition() != 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                // no observed statements
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame
            if ((this.isActiveAndEnabled != 0) && (visible == 0))
            {
                // no observed statements
            }

            // path: TutorialController.Start -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame
            if ((MapMove.StagePosition <= 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                // no observed statements
            }

            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame -> TutorialController/<UnlockAfterFrame>d__15.MoveNext
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                InteractionLock.IsLocked = false;                          // IL_002F
                InteractionLock.IsLocked = 0;                              // IL_002F
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame -> TutorialController/<UnlockAfterFrame>d__15.MoveNext
            if ((TutorialController.tutorialCondition.IsMeetCondition() != 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                InteractionLock.IsLocked = false;                          // IL_002F
                InteractionLock.IsLocked = 0;                              // IL_002F
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame -> TutorialController/<UnlockAfterFrame>d__15.MoveNext
            if ((this.isActiveAndEnabled != 0) && (visible == 0))
            {
                InteractionLock.IsLocked = false;                          // IL_002F
                InteractionLock.IsLocked = 0;                              // IL_002F
            }

            // path: TutorialController.Start -> TutorialController.SetChatWindowVisible -> TutorialController.UnlockAfterFrame -> TutorialController/<UnlockAfterFrame>d__15.MoveNext
            if ((MapMove.StagePosition <= 0) && (this.isActiveAndEnabled != 0) && (visible == 0))
            {
                InteractionLock.IsLocked = false;                          // IL_002F
                InteractionLock.IsLocked = 0;                              // IL_002F
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            if (MapMove.StagePosition > 0)
            {
                Component.gameObject.SetActive(false);                     // IL_000F
            }

            if (MapMove.StagePosition <= 0)
            {
                SetChatWindowVisible(true);                                // IL_0017
                StoryTelling();                                            // IL_001D
            }
        }

        [UnityLifecycle]
        // confidence partial; gaps: singleton-plumbing
        void Awake()
        {
            if (TutorialController.Instance == null)
            {
                TutorialController.Instance = this;                        // IL_000E
            }

            if (TutorialController.Instance != null)
            {
                Destroy(this.gameObject);                                  // IL_001B
            }
        }
    }
}
