// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Core
{
    class SaveLoadController : MonoBehaviour
    {
        [InspectorCallable]
        // reached from: TitleSceneManager.QuitGame, BackButton.BackToMain, SaveLoadController.OnApplicationQuit, SaveLoadController.SavePlayData, TitleSceneManager.InitPlayerData, TitleSceneManager.LoadStoryScene
        // confidence derived
        void SavePlayData()
        {
            // path: TitleSceneManager.QuitGame -> SaveLoadController.SavePlayData
            Save("StagePosition", MapMove.StagePosition);              // IL_000A

            // path: TitleSceneManager.InitPlayerData -> TitleSceneManager.LoadStoryScene -> SaveLoadController.SavePlayData
            if (TitleSceneManager.saveLoadController.LoadPlayData() == -1)
            {
                Save("StagePosition", MapMove.StagePosition);              // IL_000A
            }
        }

        [InspectorCallable]
        // reached from: TitleSceneManager.InitPlayerData, TitleSceneManager.LoadStoryScene, TitleSceneManager.Start
        // confidence derived
        int LoadPlayData()
        {
            MapMove.StagePosition = PlayerPrefs.GetInt("StagePosition", -1); // IL_000B
        }

        [InspectorCallable]
        // reached from: TitleSceneManager.InitPlayerData, SaveLoadController.InitPlayData
        // confidence derived
        void InitPlayData()
        {
            Save("StagePosition", -1);                                 // IL_0006
        }

        [UnityLifecycle]
        // confidence verified
        void OnApplicationQuit()
        {
            SavePlayData();                                            // IL_0001
        }

        [UnityLifecycle]
        // confidence partial; gaps: singleton-plumbing
        void Awake()
        {
            if (null == SaveLoadController._instance)
            {
                SaveLoadController._instance = this;                       // IL_000E
            }

            if (null != SaveLoadController._instance)
            {
                Destroy(this.gameObject);                                  // IL_0025
            }
        }
    }
}
