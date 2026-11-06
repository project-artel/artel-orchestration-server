// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type
// created by: Scenes.GameClearController.magicCard, Scenes.GameClearController.spellCard, Cards.CardManager.cardPrefab

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.UI
{
    class DraggableCard : MonoBehaviour
    {
        [UnityLifecycle]
        // confidence verified
        void OnTriggerExit2D(Collider2D p0)
        {
            if (other.CompareTag(Component.tag) != 0)
            {
                DraggableCard.combineZone = null;                          // IL_0010
            }
        }

        [UnityLifecycle]
        // confidence verified
        void OnTriggerEnter2D(Collider2D p0)
        {
            if (other.CompareTag(Component.tag) != 0)
            {
                DraggableCard.combineZone = other.GetComponent();          // IL_0015
            }
        }

        [UnityLifecycle]
        // confidence verified
        void OnEndDrag(PointerEventData p0)
        {
            if ((DraggableCard.combineZone.CompareTag(Component.tag) != 0) && (DraggableCard.combineZone != null))
            {
                DraggableCard.combineZone.AddCard(Component.gameObject);   // IL_0039
            }

            if ((DraggableCard.combineZone == null) || ((DraggableCard.combineZone.CompareTag(Component.tag) == 0) && (DraggableCard.combineZone != null)))
            {
                Component.transform.localPosition = Vector3.zero;          // IL_005B
            }
        }

        [UnityLifecycle]
        // confidence verified
        void OnDrag(PointerEventData p0)
        {
            Component.transform.position = Input.mousePosition;        // IL_000B
        }

        [UnityLifecycle]
        // confidence verified
        void OnBeginDrag(PointerEventData p0)
        {
            DraggableCard.originalParent = this.transform.parent;      // IL_000C
        }

        [UnityLifecycle]
        // confidence verified
        void Awake()
        {
            DraggableCard.canvasGroup = this.GetComponent();           // IL_0007
        }
    }
}
