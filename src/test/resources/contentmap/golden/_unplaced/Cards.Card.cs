// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type
// created by: Scenes.GameClearController.magicCard, Scenes.GameClearController.spellCard, Cards.CardManager.cardPrefab

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Cards
{
    class Card : MonoBehaviour
    {
        [InspectorCallable]
        // reached from: CardManager.CardMouseUp, CardManager.AddCard, CardManager.Update, Card.OnMouseUp, CombineZone.ClearDropZone, CardManager.CardAlignment
        // called by: Cards.Card.OnMouseUp, Cards.CardManager.AddCard, Combat.UI.CombineZone.ClearDropZone
        // confidence partial; gaps: callee-condition-not-composed
        void MoveTransform(Prs p0, bool p1, float p2)
        {
            // path: CardManager.CardMouseUp -> Card.MoveTransform
            if (useDotween != 0)
            {
                this.transform.position = Prs.pos;                         // IL_0011
                this.transform.rotation = Prs.rot;                         // IL_0024
                this.transform.localScale = Prs.scale;                     // IL_0037
            }

            // path: CardManager.CardAlignment -> Card.MoveTransform
            if (1 != 0)
            {
                this.transform.position = Prs.pos;                         // IL_0011
                this.transform.rotation = Prs.rot;                         // IL_0024
                this.transform.localScale = Prs.scale;                     // IL_0037
            }

            // path: CardManager.CardMouseUp -> Card.MoveTransform
            if (useDotween == 0)
            {
                Component.transform.position = Prs.pos;                    // IL_004A
                Component.transform.rotation = Prs.rot;                    // IL_005B
                Component.transform.localScale = Prs.scale;                // IL_006C
            }

            // path: CardManager.CardAlignment -> Card.MoveTransform
            if (1 == 0)
            {
                Component.transform.position = Prs.pos;                    // IL_004A
                Component.transform.rotation = Prs.rot;                    // IL_005B
                Component.transform.localScale = Prs.scale;                // IL_006C
            }
        }

        [InspectorCallable]
        // reached from: CardManager.AddCard
        // called by: Battle.Turns.PlayerTurn.OnStart
        // confidence derived
        void Setup(Word p0)
        {
            Card.word = word;                                          // IL_0002

            if (Word.tag == "Spell")
            {
                Component.GetComponent().sprite = Card.magicCard;          // IL_0025
            }

            if (Word.tag != "Spell")
            {
                Component.GetComponent().sprite = Card.typeCard;           // IL_0038
            }

            Card.nameTMP.text = Word.name;                             // IL_004E
            Card.cardType = Word.magicType;                            // IL_005F
        }

        [UnityLifecycle]
        // confidence verified
        void OnMouseUp()
        {
            InteractionLock.IsLocked;                                  // IL_0000

            if (InteractionLock.IsLocked == 0)
            {
                CardManager.Inst;                                          // IL_0008
                CardManager.Inst.CardMouseUp();                            // IL_000D
                CardManager.Inst;                                          // IL_0012
                CardManager.selectCard = this;                             // IL_0018
            }
        }

        [UnityLifecycle]
        // reached from: Card.OnMouseUp
        // confidence derived
        Prs(Vector3 p0, Quaternion p1, Vector3 p2)
        {
            if ((InteractionLock.IsLocked == 0) && (((CombineZone.spellCards.Count == 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.magicTypeCards.Count == 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("Target") != 0) && (CardManager.onPushArea3 != 0) && (((CardManager.onPushArea2 == 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("MagicType") == 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0))))))))
            {
                Prs.pos = pos;                                             // IL_0008
                Prs.rot = rot;                                             // IL_000F
                Prs.scale = scale;                                         // IL_0016
            }
        }

        [UnityLifecycle]
        // confidence verified
        void OnMouseDown()
        {
            InteractionLock.IsLocked;                                  // IL_0000

            if (InteractionLock.IsLocked == 0)
            {
                CheckHighestCard();                                        // IL_0009
            }
        }

        [UnityLifecycle]
        // reached from: Card.OnMouseDown
        // confidence partial; gaps: callee-condition-not-composed, unread-condition
        void CheckHighestCard()
        {
            // unresolved condition (subject lost): RaycastHit2D.transform.gameObject.GetComponent() != null
            if ((/* unknown */))
            {
                CardManager.Inst;                                          // IL_00C7
                CardManager.Inst.CardMouseDown();                          // IL_00CC
                CardManager.Inst;                                          // IL_00D1
                CardManager.selectCard = RaycastHit2D.transform.gameObject.GetComponent(); // IL_00D8
            }
        }

        [UnityLifecycle]
        // confidence verified
        void OnMouseExit()
        {
            CardManager.Inst;                                          // IL_0000
            CardManager.Inst.CardMouseExit();                          // IL_0006
        }

        [UnityLifecycle]
        // confidence verified
        void OnMouseOver()
        {
            InteractionLock.IsLocked;                                  // IL_0000

            if (InteractionLock.IsLocked == 0)
            {
                CardManager.Inst;                                          // IL_0008
                CardManager.Inst.CardMouseOver();                          // IL_000E
            }
        }
    }
}
