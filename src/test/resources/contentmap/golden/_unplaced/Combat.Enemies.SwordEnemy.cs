// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type
// created by: Combat.Enemies.EnemyPoolController.enemyDataContainer

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Enemies
{
    class SwordEnemy : MonoBehaviour
    {
        [InspectorCallable]
        // confidence verified
        void Attack(float p0)
        {
            Attack();                                                  // IL_0002

            // unresolved condition (subject lost): distanceToPlayer < Enemy.attackRange
            Player.PlayerInt();                                        // IL_0010
            Player.PlayerInt().TakeHit(Enemy.Damage);                  // IL_001B
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            Start();                                                   // IL_0001
        }
    }
}
