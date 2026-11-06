// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Scenes
{
    class GameClearController : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void Update()
        {
            InteractionLock.IsLocked;                                  // IL_0000

            if ((GameClearController.flag == 0) && (/* gesture: {"input": "key:any (down)", "offset": 26} */) && (SceneManager.GetActiveScene().name == "GameClearScene") && (InteractionLock.IsLocked == 0))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_001A
                GameClearController.text.SetActive(true);                  // IL_0030
                ShowGettedCard();                                          // IL_0036
                GameClearController.flag = 1;                              // IL_003D
            }

            if ((GameClearController.flag != 0) && (/* gesture: {"input": "key:any (down)", "offset": 26} */) && (SceneManager.GetActiveScene().name == "GameClearScene") && (InteractionLock.IsLocked == 0))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_001A
                SceneManager.LoadScene("Map_scene");                       // IL_0048
            }

            if ((/* gesture: {"input": "key:any (down)", "offset": 78} */) && (SceneManager.GetActiveScene().name != "GameClearScene") && (InteractionLock.IsLocked == 0))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_004E
                SceneManager.LoadScene("Map_scene");                       // IL_005A
            }
        }

        [UnityLifecycle]
        // reached from: GameClearController.Update
        // confidence derived/partial; gaps: composed-on-same-object
        void ShowGettedCard()
        {
            if ((GameClearController.flag == 0) && (/* gesture: {"input": "key:any (down)", "offset": 26} */) && (SceneManager.GetActiveScene().name == "GameClearScene") && (InteractionLock.IsLocked == 0))
            {
                StageDataSingleton.Instance;                               // IL_000F
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_001A
                StageDataSingleton.Instance;                               // IL_002A
            }

            if ((GameClearController.flag == 0) && (/* gesture: {"input": "key:any (down)", "offset": 26} */) && (SceneManager.GetActiveScene().name == "GameClearScene") && (InteractionLock.IsLocked == 0) && ((MapMove.StagePosition - 1) == StageDataSingleton.stagePosition))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_001A
                StageDataSingleton.Instance;                               // IL_0039
            }

            if ((GameClearController.flag == 0) && (/* gesture: {"input": "key:any (down)", "offset": 26} */) && (SceneManager.GetActiveScene().name == "GameClearScene") && (InteractionLock.IsLocked == 0) && (StageDataSingleton.stagePosition == 0) && ((MapMove.StagePosition - 1) == StageDataSingleton.stagePosition))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_001A
                Instantiate(GameClearController.spellCard);                // IL_007A
                GameObject.GetComponentInChildren().text = Word.name, true; // IL_0098
                GameObject.GetComponent().SetOrder(0);                     // IL_00A3
            }

            if ((GameClearController.flag == 0) && (/* gesture: {"input": "key:any (down)", "offset": 26} */) && (SceneManager.GetActiveScene().name == "GameClearScene") && (InteractionLock.IsLocked == 0) && (StageDataSingleton.stagePosition == 1) && ((MapMove.StagePosition - 1) == StageDataSingleton.stagePosition))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_001A
                Instantiate(GameClearController.magicCard);                // IL_00C8
                GameObject.GetComponent().SetOrder(0);                     // IL_00D4
                GameObject.GetComponentInChildren().text = Word.name, true; // IL_00F1
            }

            if ((GameClearController.flag == 0) && (/* gesture: {"input": "key:any (down)", "offset": 26} */) && (SceneManager.GetActiveScene().name == "GameClearScene") && (InteractionLock.IsLocked == 0) && (StageDataSingleton.stagePosition == 2) && ((MapMove.StagePosition - 1) == StageDataSingleton.stagePosition))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_001A
                Instantiate(GameClearController.magicCard);                // IL_0116
                GameObject.GetComponentInChildren().text = Word.name, true; // IL_0134
                GameObject.GetComponent().SetOrder(0);                     // IL_013F
                Instantiate(GameClearController.magicCard);                // IL_0163
                GameObject.GetComponentInChildren().text = Word.name, true; // IL_0181
                GameObject.GetComponent().SetOrder(0);                     // IL_018C
            }

            if ((GameClearController.flag == 0) && (/* gesture: {"input": "key:any (down)", "offset": 26} */) && (SceneManager.GetActiveScene().name == "GameClearScene") && (InteractionLock.IsLocked == 0) && (StageDataSingleton.stagePosition == 3) && ((MapMove.StagePosition - 1) == StageDataSingleton.stagePosition))
            {
                if (Input.GetKeyDown(KeyCode.any)) { ... }                 // IL_001A
                Instantiate(GameClearController.magicCard);                // IL_01B1
                GameObject.GetComponentInChildren().text = Word.name, true; // IL_01CF
                GameObject.GetComponent().SetOrder(0);                     // IL_01DA
                Instantiate(GameClearController.magicCard);                // IL_01FE
                GameObject.GetComponentInChildren().text = Word.name, true; // IL_021C
                GameObject.GetComponent().SetOrder(0);                     // IL_0227
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            GameClearController.sceneName = SceneManager.GetActiveScene().name; // IL_000E

            if (SceneManager.GetActiveScene().name == "GameClearScene")
            {
                StageDataSingleton.Instance;                               // IL_0025
            }

            if ((StageDataSingleton.stagePosition == 4) && (SceneManager.GetActiveScene().name == "GameClearScene"))
            {
                SceneManager.LoadScene("EndingScene");                     // IL_0037
            }

            if (SceneManager.GetActiveScene().name == "GameClearScene")
            {
                GameClearController.text.SetActive(false);                 // IL_0043
            }
        }
    }
}
