// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Enemies
{
    class EnemyPoolController : MonoBehaviour
    {
        [InspectorCallable]
        // reached from: EnemyTestManager.SpawnEnemies, BattleWaveController.Start1, StageManager.Start
        // called by: Combat.Stage.StageManager.SetupBattle
        // confidence derived/partial; gaps: callee-condition-not-composed
        GameObject SpawnObject(float p0, float p1, int p2)
        {
            if (EnemyPoolController.EnemyPools.Item[_].Count > 0)
            {
                (not a simple receiver).SetActive(true);                   // IL_0028
                GameObject.GetComponent().position = /* ? */;              // IL_0051
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            InitPool();                                                // IL_0001
        }

        [UnityLifecycle]
        // reached from: EnemyPoolController.Start
        // confidence derived
        void InitPool()
        {
            // unresolved condition (subject lost): i < EnemyPoolController.enemyDataContainer.GetGearNum()
            EnemyPoolController.enemyDataContainer.GetGearData();      // IL_000B
            MakeObjects(_, 5, EnemyPoolController.EnemyPools.Item[_]); // IL_0030
            goto IL_0004;                                              // loop

            // unresolved condition (subject lost): i < EnemyPoolController.enemyDataContainer.GetGearNum()
            EnemyPoolController.enemyDataContainer.GetGearNum();       // IL_0040
            goto IL_0004;                                              // loop
        }

        [UnityLifecycle]
        // reached from: EnemyPoolController.Start
        // confidence partial; gaps: callee-condition-not-composed
        void MakeObjects(EnemyData p0, int p1, List<GameObject> p2)
        {
            // unresolved condition (subject lost): (EnemyData.id / 3) == EnemyPoolController.stagePosition
            // unresolved condition (subject lost): i < num
            // unresolved condition (subject lost): (EnemyData.id / 3) != EnemyPoolController.stagePosition
            if (((EnemyData.id > 11) && (EnemyPoolController.stagePosition == 4)))
            {
                Instantiate(EnemyData.prefab);                             // IL_0046
                GameObject.GetComponent().InitEnemyData();                 // IL_0053
                List`1.Item[_].SetActive(false);                           // IL_0067
            }
        }
    }
}
