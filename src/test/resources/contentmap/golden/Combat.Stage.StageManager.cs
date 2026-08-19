// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Stage
{
    class StageManager : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            StageManager.battleWaveController = Object.FindObjectOfType(); // IL_0006
            StageDataSingleton.Instance;                               // IL_000B
            LoadStage();                                               // IL_0018
            SetupBattle(StageManager.currentStageData, _);             // IL_0025
        }

        [UnityLifecycle]
        // reached from: StageManager.Start
        // confidence derived
        void SetupBattle(StageData p0, int p1)
        {
            SetBackground(StageData.background, _);                    // IL_0008
            BattleWaveController.battleScript = /* ? */;               // IL_0020
            StageManager.battleWaveController.Start1();                // IL_002B
        }

        [UnityLifecycle]
        // reached from: StageManager.Start
        // confidence derived
        void SetBackground(Sprite p0, int p1)
        {
            StageManager.audioSource.Play();                           // IL_0028

            if (stagePosition == 3)
            {
                (not a simple receiver).SetActive(false);                  // IL_0033
                StageManager.rainyBackground.SetActive(true);              // IL_003F
            }

            if (stagePosition != 3)
            {
                (not a simple receiver).SetActive(true);                   // IL_0048
                StageManager.rainyBackground.SetActive(false);             // IL_0054
            }

            // unresolved condition (subject lost): GameObject.Find("PlainBackground").GetComponent() != null
            if ((GameObject.Find("PlainBackground") != null))
            {
                (not a simple receiver).sprite = background;               // IL_0074
            }
        }

        [UnityLifecycle]
        // reached from: StageManager.Start
        // confidence derived
        void LoadStage(int p0)
        {
            // unresolved condition (subject lost): StageData.stageID == stagePosition
            // unresolved condition (subject lost): StageData.stageID != stagePosition
            if ((StageManager.stageDataList.GetEnumerator().MoveNext() != 0))
            {
                StageManager.currentStageData = List`1.GetEnumerator().Current; // IL_0021
            }
        }
    }
}
