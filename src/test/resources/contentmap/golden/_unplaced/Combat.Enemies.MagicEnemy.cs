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
    class MagicEnemy : MonoBehaviour
    {
        [InspectorCallable]
        // confidence verified
        void Attack(float p0)
        {
            Enemy.Animator.RangeAttack();                              // IL_0006
            Instantiate(MagicEnemy.fireShoot);                         // IL_0021
            Object.Instantiate(MagicEnemy.fireShoot, Component.transform.position, Quaternion.identity).GetComponent().InitProjectileDamage(Enemy.Damage); // IL_0031
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            Start();                                                   // IL_0001
        }
    }
}
