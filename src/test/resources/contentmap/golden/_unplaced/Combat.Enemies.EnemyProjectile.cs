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
    class EnemyProjectile : MonoBehaviour
    {
        [InspectorCallable]
        // reached from: MagicEnemy.Attack, EnemyProjectile.InitProjectileDamage
        // confidence derived
        void InitProjectileDamage(int p0)
        {
            EnemyProjectile.damage = damage;                           // IL_0002
        }

        [UnityLifecycle]
        // confidence verified
        void OnTriggerEnter2D(Collider2D p0)
        {
            if (collision.CompareTag("Me") != 0)
            {
                Player.PlayerInt();                                        // IL_000D
                Player.PlayerInt().TakeHit(EnemyProjectile.damage);        // IL_0018
                Destroy(this.gameObject);                                  // IL_0023
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Update()
        {
            if (Vector3.x < -10)
            {
                Destroy(this.gameObject);                                  // IL_001D
            }

            if (Vector3.x >= -10)
            {
                Component.transform.position = Vector3.op_Addition(Component.transform.position, Vector3.op_Multiply(EnemyProjectile.moveVector, Time.deltaTime)); // IL_0049
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            EnemyProjectile.moveVector = /* ? */;                      // IL_001C
        }
    }
}
