// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Tutorial
{
    class TutorialChatWindow : MonoBehaviour
    {
        [UnityLifecycle]
        [InspectorCallable]
        // reached from: TutorialController.Update, TutorialController.ProceedTutorial, TutorialController.OnTriggerTutorial, TutorialChatWindow.SetSpeakerImage, TutorialController.Start
        // called by: Tutorial.TutorialController.ProceedTutorial, Tutorial.TutorialController.Update
        // confidence derived
        void SetSpeakerImage(Sprite p0)
        {
            // path: TutorialController.Update -> TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> TutorialChatWindow.SetSpeakerImage
            if ((TutorialController.waitingForAcknowledge == 0) && (Object.Equals(FLAG_014_END_TUTORIAL) == 0) && (TutorialController.tutorialCondition.IsMeetCondition() != 0))
            {
                TutorialChatWindow.speakerImage.sprite = image;            // IL_0007
            }

            // path: TutorialController.ProceedTutorial -> TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> TutorialChatWindow.SetSpeakerImage
            if (TutorialController.tutorialCondition.IsMeetCondition() != 0)
            {
                TutorialChatWindow.speakerImage.sprite = image;            // IL_0007
            }

            // path: TutorialController.OnTriggerTutorial -> TutorialController.StoryTelling -> TutorialChatWindow.SetSpeakerImage
            TutorialChatWindow.speakerImage.sprite = image;            // IL_0007

            // path: TutorialController.Start -> TutorialController.StoryTelling -> TutorialChatWindow.SetSpeakerImage
            if (MapMove.StagePosition <= 0)
            {
                TutorialChatWindow.speakerImage.sprite = image;            // IL_0007
            }
        }
    }
}
