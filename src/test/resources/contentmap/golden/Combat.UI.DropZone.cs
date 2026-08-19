// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.UI
{
    class DropZone : MonoBehaviour
    {
        [UnityLifecycle]
        [InspectorCallable]
        // reached from: CardManager.CardMouseUp, Card.OnMouseUp, DropZone.GetCard
        // called by: Cards.Card.OnMouseUp, Cards.CardManager.CardMouseUp
        // confidence derived/verified
        void GetCard(GameObject p0)
        {
            // path: CardManager.CardMouseUp -> DropZone.GetCard
            if (((CombineZone.spellCards.Count == 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.magicTypeCards.Count == 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("Target") != 0) && (CardManager.onPushArea3 != 0) && (((CardManager.onPushArea2 == 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("MagicType") == 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))))))
            {
                DropZone.combineZone.AddCard();                            // IL_0007
            }

            // path: Card.OnMouseUp -> CardManager.CardMouseUp -> DropZone.GetCard
            if ((InteractionLock.IsLocked == 0) && (((CombineZone.spellCards.Count == 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.magicTypeCards.Count == 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("Target") != 0) && (CardManager.onPushArea3 != 0) && (((CardManager.onPushArea2 == 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("MagicType") == 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0))))))))
            {
                DropZone.combineZone.AddCard();                            // IL_0007
            }

            // path: DropZone.GetCard
            DropZone.combineZone.AddCard();                            // IL_0007
        }
    }
}
