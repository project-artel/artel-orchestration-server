// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Spells
{
    class SpellObj : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void OnTriggerEnter2D(Collider2D p0)
        {
            if (collision.CompareTag(SpellObj.target.gameObject.tag) != 0)
            {
                SpellObj.moveVector = Vector3.zero;                        // IL_0021
                SpellObj.animator.SetTrigger("Hit");                       // IL_0041
            }

            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0))
            {
                CalculateDamage(SpellObj.damage, Enemy.enemyType);         // IL_0070
                Component.GetComponent().TakeHit(SpellObj.CalculateDamage(SpellObj.damage, Enemy.enemyType)); // IL_0075
            }

            if ((collision.CompareTag("Enemy") == 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0))
            {
                CalculateDamage(SpellObj.damage, Holy);                    // IL_008A
                Component.GetComponent().TakeHit(SpellObj.CalculateDamage(SpellObj.damage, Holy)); // IL_008F
            }

            if (collision.CompareTag(SpellObj.target.gameObject.tag) != 0)
            {
                StartCoroutine(DestoryCounter());                          // IL_0096
            }
        }

        [UnityLifecycle]
        // reached from: SpellObj.OnTriggerEnter2D
        // confidence derived
        IEnumerator DestoryCounter()
        {
            // path: SpellObj.OnTriggerEnter2D -> SpellObj.DestoryCounter
            if (collision.CompareTag(SpellObj.target.gameObject.tag) != 0)
            {
                // no observed statements
            }

            // path: SpellObj.OnTriggerEnter2D -> SpellObj.DestoryCounter -> SpellObj/<DestoryCounter>d__14.MoveNext
            if (collision.CompareTag(SpellObj.target.gameObject.tag) != 0)
            {
                Destroy(Component.gameObject);                             // IL_0044
            }
        }

        [UnityLifecycle]
        // reached from: SpellObj.OnTriggerEnter2D
        // confidence derived
        int CalculateDamage(int p0, MagicType p1)
        {
            if (((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0)) || ((collision.CompareTag("Enemy") == 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0)))
            {
                SpellObj.magicAffinityTable.GetAffinity(SpellObj.magicType, _); // IL_0035
            }
        }

        [UnityLifecycle]
        // reached from: SpellObj.OnTriggerEnter2D
        // confidence derived
        float GetAffinity(MagicType p0, MagicType p1)
        {
            if (((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0)) || ((collision.CompareTag("Enemy") == 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0)))
            {
                MagicToNum();                                              // IL_000D
                MagicToNum();                                              // IL_001E
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            SpellObj.animator = this.GetComponent();                   // IL_0007
        }

        [InspectorCallable]
        // confidence verified
        void InitProjectileDamage(int p0)
        {
            SpellObj.damage = damage;                                  // IL_0002
        }
    }
}
