// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Scenes
{
    class TitleSceneManager : MonoBehaviour
    {
        [UnityEvent(wired: "Canvas/ExitButton")]
        // confidence verified
        void QuitGame()
        {
            SaveLoadController.Instance;                               // IL_0000
            SaveLoadController.Instance.SavePlayData();                // IL_0005
            SaveLoadController.Instance;                               // IL_000A
            SaveLoadController.Instance.QuitGame();                    // IL_000F
        }

        [UnityEvent(wired: "Canvas/MapSceneButton")]
        // confidence verified
        void InitPlayerData()
        {
            SaveLoadController.Instance;                               // IL_0000
            SaveLoadController.Instance.InitPlayData();                // IL_0005
            LoadStoryScene();                                          // IL_000B
        }

        [UnityEvent(wired: "Canvas/continue")]
        // reached from: TitleSceneManager.InitPlayerData, TitleSceneManager.LoadStoryScene
        // confidence derived
        void LoadStoryScene()
        {
            TitleSceneManager.saveLoadController.LoadPlayData();       // IL_0006

            if (TitleSceneManager.saveLoadController.LoadPlayData() == -1)
            {
                MapMove.StagePosition = 0;                                 // IL_000F
                TitleSceneManager.saveLoadController.SavePlayData();       // IL_001A
                SceneManager.LoadScene("StoryScene");                      // IL_0024
            }

            if (TitleSceneManager.saveLoadController.LoadPlayData() != -1)
            {
                SceneManager.LoadScene("Map_scene");                       // IL_002F
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            TitleSceneManager.saveLoadController = GameObject.Find("SaveLoadController").GetComponent(); // IL_0010
            TitleSceneManager.saveLoadController.LoadPlayData();       // IL_001B

            if (TitleSceneManager.saveLoadController.LoadPlayData() == -1)
            {
                TitleSceneManager.continueButton.SetActive(false);         // IL_002A
            }
        }
    }
}
