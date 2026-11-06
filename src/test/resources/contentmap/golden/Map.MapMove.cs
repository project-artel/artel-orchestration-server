// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Map
{
    class MapMove : MonoBehaviour
    {
        [UnityLifecycle]
        [InspectorCallable]
        // reached from: MapMove.SelectStage, MapMove.Update
        // called by: Map.MapMove.CharacterMove
        // confidence derived/verified
        void SelectStage(int p0)
        {
            // path: MapMove.SelectStage
            StageDataSingleton.Instance;                               // IL_0000
            StageDataSingleton.stagePosition = stagePosition;          // IL_0006
            SceneManager.LoadScene("TurnBattleScene");                 // IL_0010

            // path: MapMove.Update -> MapMove.CharacterMove -> MapMove.SelectStage
            if ((/* gesture: {"input": "key:Return (down)", "offset": 704} */) && (InteractionLock.IsLocked == 0))
            {
                StageDataSingleton.Instance;                               // IL_0000
                StageDataSingleton.stagePosition = stagePosition;          // IL_0006
                SceneManager.LoadScene("TurnBattleScene");                 // IL_0010
                if (Input.GetKeyDown(KeyCode.Return)) { ... }              // IL_02C0
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Update()
        {
            CharacterMove();                                           // IL_0001
            ShowStage();                                               // IL_0007
            ShowBattle(MapMove.StagePosition);                         // IL_0012
        }

        [UnityLifecycle]
        // reached from: MapMove.Update, MapMove.Start
        // confidence derived/partial; gaps: callee-condition-not-composed
        void ShowBattle(int p0)
        {
            if (stagePosition == 1)
            {
                GameObject.Find("Background").GetComponent().sprite = MapMove.stage1; // IL_0032
            }

            if (stagePosition == 2)
            {
                GameObject.Find("Background").GetComponent().sprite = MapMove.stage2; // IL_004D
            }

            if (stagePosition == 3)
            {
                GameObject.Find("Background").GetComponent().sprite = MapMove.stage3; // IL_0068
            }

            if (stagePosition == 4)
            {
                GameObject.Find("Background").GetComponent().sprite = MapMove.stage4; // IL_0083
            }

            if (stagePosition == 5)
            {
                GameObject.Find("Background").GetComponent().sprite = MapMove.stage4; // IL_009E
            }
        }

        [UnityLifecycle]
        // reached from: MapMove.Update
        // confidence derived
        void ShowStage()
        {
            MapMove.stage.text = String.Concat("Stage : ", Int32.ToString()); // IL_001A
        }

        [UnityLifecycle]
        // reached from: MapMove.Update
        // confidence derived
        void CharacterMove()
        {
            InteractionLock.IsLocked;                                  // IL_0000

            if ((MapMove.StagePosition >= 1) && (((/* gesture: {"input": "key:RightArrow (down)", "offset": 21} */) && (MapMove.position == 0) && (InteractionLock.IsLocked == 0)) || ((/* gesture: {"input": "key:UpArrow (down)", "offset": 33} */) && (MapMove.position == 0) && (InteractionLock.IsLocked == 0))))
            {
                if (Input.GetKeyDown(KeyCode.RightArrow)) { ... }          // IL_0015
                if (Input.GetKeyDown(KeyCode.UpArrow)) { ... }             // IL_0021
                MapMove.character.transform.position = MapMove.battle1.transform.position; // IL_0057
                MapMove.position += 1;                                     // IL_0066
            }

            if ((MapMove.StagePosition >= 2) && (/* gesture: {"input": "key:RightArrow (down)", "offset": 129} */) && (MapMove.position == 1) && (InteractionLock.IsLocked == 0))
            {
                if (Input.GetKeyDown(KeyCode.RightArrow)) { ... }          // IL_0081
                MapMove.character.transform.position = MapMove.battle2.transform.position; // IL_00B1
                MapMove.position += 1;                                     // IL_00C0
            }

            if (((/* gesture: {"input": "key:LeftArrow (down)", "offset": 202} */) && (MapMove.position == 1) && (InteractionLock.IsLocked == 0)) || ((/* gesture: {"input": "key:DownArrow (down)", "offset": 214} */) && (MapMove.position == 1) && (InteractionLock.IsLocked == 0)))
            {
                if (Input.GetKeyDown(KeyCode.LeftArrow)) { ... }           // IL_00CA
                if (Input.GetKeyDown(KeyCode.DownArrow)) { ... }           // IL_00D6
                MapMove.character.transform.position = MapMove.village.transform.position; // IL_0101
                MapMove.position = -1;                                     // IL_0110
            }

            if ((MapMove.StagePosition >= 3) && (((/* gesture: {"input": "key:RightArrow (down)", "offset": 299} */) && (MapMove.position == 2) && (InteractionLock.IsLocked == 0)) || ((/* gesture: {"input": "key:UpArrow (down)", "offset": 311} */) && (MapMove.position == 2) && (InteractionLock.IsLocked == 0))))
            {
                if (Input.GetKeyDown(KeyCode.RightArrow)) { ... }          // IL_012B
                if (Input.GetKeyDown(KeyCode.UpArrow)) { ... }             // IL_0137
                MapMove.character.transform.position = MapMove.battle3.transform.position; // IL_0167
                MapMove.position += 1;                                     // IL_0176
            }

            if ((/* gesture: {"input": "key:LeftArrow (down)", "offset": 384} */) && (MapMove.position == 2) && (InteractionLock.IsLocked == 0))
            {
                if (Input.GetKeyDown(KeyCode.LeftArrow)) { ... }           // IL_0180
                MapMove.character.transform.position = MapMove.battle1.transform.position; // IL_01AB
                MapMove.position = -1;                                     // IL_01BA
            }

            if ((MapMove.StagePosition >= 4) && (/* gesture: {"input": "key:RightArrow (down)", "offset": 469} */) && (MapMove.position == 3) && (InteractionLock.IsLocked == 0))
            {
                if (Input.GetKeyDown(KeyCode.RightArrow)) { ... }          // IL_01D5
                MapMove.character.transform.position = MapMove.boss.transform.position; // IL_0205
                MapMove.position += 1;                                     // IL_0214
            }

            if (((/* gesture: {"input": "key:LeftArrow (down)", "offset": 542} */) && (MapMove.position == 3) && (InteractionLock.IsLocked == 0)) || ((/* gesture: {"input": "key:DownArrow (down)", "offset": 554} */) && (MapMove.position == 3) && (InteractionLock.IsLocked == 0)))
            {
                if (Input.GetKeyDown(KeyCode.LeftArrow)) { ... }           // IL_021E
                if (Input.GetKeyDown(KeyCode.DownArrow)) { ... }           // IL_022A
                MapMove.character.transform.position = MapMove.battle2.transform.position; // IL_0255
                MapMove.position = -1;                                     // IL_0264
            }

            if ((/* gesture: {"input": "key:LeftArrow (down)", "offset": 642} */) && (((MapMove.position == 4) && (InteractionLock.IsLocked == 0)) || ((MapMove.position == 5) && (InteractionLock.IsLocked == 0))))
            {
                if (Input.GetKeyDown(KeyCode.LeftArrow)) { ... }           // IL_0282
                MapMove.character.transform.position = MapMove.battle3.transform.position; // IL_02AA
                MapMove.position = -1;                                     // IL_02B9
            }

            if ((/* gesture: {"input": "key:Return (down)", "offset": 704} */) && (InteractionLock.IsLocked == 0))
            {
                if (Input.GetKeyDown(KeyCode.Return)) { ... }              // IL_02C0
                SelectStage(MapMove.position);                             // IL_02CE
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            InitShowBattles();                                         // IL_0001
        }

        [UnityLifecycle]
        // reached from: MapMove.Start
        // confidence derived
        void InitShowBattles()
        {
            // unresolved condition (subject lost): i < MapMove.StagePosition
            ShowBattle();                                              // IL_0006
            goto IL_0004;                                              // loop

            WordPosition(MapMove.StagePosition);                       // IL_001D
        }

        [UnityLifecycle]
        // reached from: MapMove.Start
        // confidence derived
        void WordPosition(int p0)
        {
            MapMove.position = stagePosition;                          // IL_0002

            if (stagePosition == 1)
            {
                MapMove.character.transform.position = MapMove.battle1.transform.position; // IL_0041
            }

            if (stagePosition == 2)
            {
                MapMove.character.transform.position = MapMove.battle2.transform.position; // IL_0062
            }

            if (stagePosition == 3)
            {
                MapMove.character.transform.position = MapMove.battle3.transform.position; // IL_0083
            }

            if (stagePosition == 4)
            {
                MapMove.character.transform.position = MapMove.boss.transform.position; // IL_00A4
            }

            if (stagePosition == 5)
            {
                MapMove.character.transform.position = MapMove.boss.transform.position; // IL_00C5
            }
        }
    }
}
