// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Enemies
{
    class BattleWaveController : MonoBehaviour
    {
        [InspectorCallable]
        // reached from: BattleWaveController.Start1, StageManager.Start
        // called by: Combat.Stage.StageManager.SetupBattle
        // confidence verified
        void Start1()
        {
            BattleWaveController.ememyPool = this.gameObject.GetComponent(); // IL_000C
            StartWave(BattleWaveController.wave);                      // IL_0018
        }

        [InspectorCallable]
        // reached from: BattleWaveController.Start1, StageManager.Start
        // called by: Combat.Stage.StageManager.SetupBattle
        // confidence derived
        void StartWave(int p0)
        {
            BattleWaveController.battleScript.GetBattleWaveDatas();    // IL_0006

            // unresolved condition (subject lost): i < BattleWaveData.enemySpawnDatasInWave.Count
            BattleWaveController.ememyPool.SpawnObject(EnemySpawnData.spawnPositionX, _, EnemySpawnData.enemyId); // IL_0066
            goto IL_002B;                                              // loop

            StartCoroutine(WaveEndSensor());                           // IL_0084
        }

        [InspectorCallable]
        // reached from: BattleWaveController.Start1, StageManager.Start
        // called by: Combat.Stage.StageManager.SetupBattle
        // confidence derived
        IEnumerator WaveEndSensor()
        {
            local waveEnd = 1;                                         // IL_002B
            goto IL_0029;                                              // loop

            // unresolved condition (subject lost): List`1.GetEnumerator().Current.activeSelf != 0
            // unresolved condition (subject lost): BattleWaveController.activatedEnemies.GetEnumerator().MoveNext() != 0
            local waveEnd = 0;                                         // IL_006E

            BattleWaveController.wave += 1;                            // IL_00BD
            BattleWaveController.battleScript.GetBattleWaveDatas();    // IL_00CE

            if (BattleWaveController.wave < BattleWaveController.battleScript.GetBattleWaveDatas().Count)
            {
                BattleWaveController.StartWave(BattleWaveController.wave); // IL_00E1
            }

            if (BattleWaveController.wave >= BattleWaveController.battleScript.GetBattleWaveDatas().Count)
            {
                MapMove.StagePosition += 1;                                // IL_00EF
                SceneManager.LoadScene("GameClearScene");                  // IL_00F9
            }
        }
    }
}
