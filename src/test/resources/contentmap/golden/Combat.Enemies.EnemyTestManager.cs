// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Enemies
{
    class EnemyTestManager : MonoBehaviour
    {
        [InspectorCallable]
        // confidence verified
        void SpawnEnemies()
        {
            EnemyTestManager.enemyPoolController.SpawnObject(0, 1, 0); // IL_0011
            EnemyTestManager.enemyPoolController.SpawnObject(4, 2, 1); // IL_0028
            EnemyTestManager.enemyPoolController.SpawnObject(8, 3, 2); // IL_003F
            InitList(EnemyTestManager.enemies);                        // IL_004C
        }

        [InspectorCallable]
        // called by: Battle.Turns.EnemyTurn.OnStart
        // confidence verified
        void PlayTurn()
        {
            InitList(EnemyTestManager.enemies);                        // IL_0007

            if (EnemyTestManager.enemies.GetEnumerator().MoveNext() != 0)
            {
                Enemy.PlayTurnAction((Vector3.x - Vector3.x));             // IL_0047
                goto IL_001A;                                              // loop
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            InitList(EnemyTestManager.enemies);                        // IL_0007
            EnemyTestManager.enemyPoolController = this.gameObject.GetComponent(); // IL_0018
        }
    }
}
