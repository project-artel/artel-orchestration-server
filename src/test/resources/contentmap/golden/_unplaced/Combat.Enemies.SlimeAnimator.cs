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
    class SlimeAnimator : MonoBehaviour
    {
        [InspectorCallable]
        // reached from: SwordEnemy.Attack, SlimeAnimator.Attack, Enemy.Attack, BossEnemy.Attack
        // confidence derived
        void Attack()
        {
            StartCoroutine(Attacking());                               // IL_0008
        }

        [InspectorCallable]
        // reached from: SwordEnemy.Attack, SlimeAnimator.Attack, Enemy.Attack, BossEnemy.Attack
        // confidence derived
        IEnumerator Attacking()
        {
            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[2]; // IL_003B

            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[3]; // IL_0072

            StartCoroutine(SlimeAnimator.Idling());                    // IL_0099
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: SwordEnemy.Attack, SlimeAnimator.RangeAttack, SlimeAnimator.TakeHit, SlimeAnimator.Attack, SlimeAnimator.MoveEnd, SlimeAnimator.Start, MagicEnemy.Attack, Enemy.Attack, BossEnemy.Attack, Enemy.TakeHit, SpellObj.OnTriggerEnter2D
        // called by: Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived
        IEnumerator Idling()
        {
            // path: Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext -> SlimeAnimator.Idling
            if (Enemy.Hp > 0)
            {
                // no observed statements
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext -> SlimeAnimator.Idling
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp > 0))
            {
                // no observed statements
            }

            // path: SwordEnemy.Attack -> Enemy.Attack -> SlimeAnimator.Attack -> SlimeAnimator.Attacking -> SlimeAnimator/<Attacking>d__11.MoveNext -> SlimeAnimator.Idling -> SlimeAnimator/<Idling>d__13.MoveNext
            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[0]; // IL_003B
            goto IL_0029;                                              // loop

            // path: Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext -> SlimeAnimator.Idling -> SlimeAnimator/<Idling>d__13.MoveNext
            if (Enemy.Hp > 0)
            {
                SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[0]; // IL_003B
                goto IL_0029;                                              // loop
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext -> SlimeAnimator.Idling -> SlimeAnimator/<Idling>d__13.MoveNext
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp > 0))
            {
                SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[0]; // IL_003B
                goto IL_0029;                                              // loop
            }

            // path: SwordEnemy.Attack -> Enemy.Attack -> SlimeAnimator.Attack -> SlimeAnimator.Attacking -> SlimeAnimator/<Attacking>d__11.MoveNext -> SlimeAnimator.Idling -> SlimeAnimator/<Idling>d__13.MoveNext
            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[1]; // IL_0072

            // path: Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext -> SlimeAnimator.Idling -> SlimeAnimator/<Idling>d__13.MoveNext
            if (Enemy.Hp > 0)
            {
                SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[1]; // IL_0072
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext -> SlimeAnimator.Idling -> SlimeAnimator/<Idling>d__13.MoveNext
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp > 0))
            {
                SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[1]; // IL_0072
            }
        }

        [InspectorCallable]
        // reached from: SlimeAnimator.RangeAttack, MagicEnemy.Attack
        // called by: Combat.Enemies.BossEnemy.StopMove, Combat.Enemies.MagicEnemy.Attack
        // confidence verified
        void RangeAttack()
        {
            StartCoroutine(RangeAttacking());                          // IL_0008
        }

        [InspectorCallable]
        // reached from: SlimeAnimator.RangeAttack, MagicEnemy.Attack
        // called by: Combat.Enemies.BossEnemy.StopMove, Combat.Enemies.MagicEnemy.Attack
        // confidence derived
        IEnumerator RangeAttacking()
        {
            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[6]; // IL_003B

            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[7]; // IL_0072

            StartCoroutine(SlimeAnimator.Idling());                    // IL_0099
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: SlimeAnimator.TakeHit, Enemy.TakeHit, SpellObj.OnTriggerEnter2D
        // called by: Combat.Enemies.Enemy.TakeHit, Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived/verified
        void TakeHit()
        {
            // path: SlimeAnimator.TakeHit
            StartCoroutine(TakeHitting());                             // IL_0008

            // path: Enemy.TakeHit -> SlimeAnimator.TakeHit
            if (Enemy.Hp > 0)
            {
                StartCoroutine(TakeHitting());                             // IL_0008
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> SlimeAnimator.TakeHit
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp > 0))
            {
                StartCoroutine(TakeHitting());                             // IL_0008
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: SlimeAnimator.TakeHit, Enemy.TakeHit, SpellObj.OnTriggerEnter2D
        // called by: Combat.Enemies.Enemy.TakeHit, Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived
        IEnumerator TakeHitting()
        {
            // path: Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting
            if (Enemy.Hp > 0)
            {
                // no observed statements
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp > 0))
            {
                // no observed statements
            }

            // path: SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext
            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[4]; // IL_0030

            // path: Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext
            if (Enemy.Hp > 0)
            {
                SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[4]; // IL_0030
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp > 0))
            {
                SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[4]; // IL_0030
            }

            // path: SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext
            StartCoroutine(SlimeAnimator.Idling());                    // IL_0057

            // path: Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext
            if (Enemy.Hp > 0)
            {
                StartCoroutine(SlimeAnimator.Idling());                    // IL_0057
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> SlimeAnimator.TakeHit -> SlimeAnimator.TakeHitting -> SlimeAnimator/<TakeHitting>d__10.MoveNext
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp > 0))
            {
                StartCoroutine(SlimeAnimator.Idling());                    // IL_0057
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: SlimeAnimator.Death, Enemy.TakeHit, SpellObj.OnTriggerEnter2D
        // called by: Combat.Enemies.Enemy.Death, Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived/verified
        void Death()
        {
            // path: SlimeAnimator.Death
            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[5]; // IL_0018

            // path: Enemy.TakeHit -> Enemy.Death -> SlimeAnimator.Death
            if (Enemy.Hp <= 0)
            {
                SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[5]; // IL_0018
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> Enemy.Death -> SlimeAnimator.Death
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp <= 0))
            {
                SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[5]; // IL_0018
            }
        }

        [InspectorCallable]
        // called by: Combat.Enemies.Enemy.StopMove
        // confidence verified
        void MoveEnd()
        {
            StartCoroutine(Idling());                                  // IL_0008
        }

        [InspectorCallable]
        // called by: Combat.Enemies.Enemy/<MoveDistance>d__17.MoveNext
        // confidence verified
        void MoveStart()
        {
            StartCoroutine(Moving());                                  // IL_0008
        }

        [InspectorCallable]
        // reached from: SlimeAnimator.MoveStart
        // called by: Combat.Enemies.Enemy/<MoveDistance>d__17.MoveNext
        // confidence derived
        IEnumerator Moving()
        {
            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[2]; // IL_003B
            goto IL_0029;                                              // loop

            SlimeAnimator.spriteRenderer.sprite = SlimeAnimator.sprites.Item[3]; // IL_0072
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            SlimeAnimator.spriteRenderer = this.GetComponent();        // IL_0007
            StartCoroutine(Idling());                                  // IL_000E
        }
    }
}
